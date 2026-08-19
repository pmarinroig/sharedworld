//! `swctl` — operator tooling for the SharedWorld server: migrations, the
//! usage snapshot (`scripts/cf-stats.sh` port), D1 dump import.

use std::path::PathBuf;

use anyhow::Context;
use clap::{Parser, Subcommand};
use sw_db::{migrate, Db, DbOptions};

#[derive(Parser)]
#[command(name = "swctl", about = "SharedWorld server operator tooling")]
struct Args {
    /// SQLite database path.
    #[arg(long, env = "SW_DB_PATH", default_value = "./sharedworld.db")]
    db: PathBuf,
    #[command(subcommand)]
    cmd: Cmd,
}

#[derive(Subcommand)]
enum Cmd {
    /// Apply pending migrations.
    Migrate,
    /// Print the usage snapshot (port of scripts/cf-stats.sh).
    Stats,
    /// Import a `wrangler d1 export` SQL dump into a fresh database, then mark
    /// the worker migrations applied and run the server-only ones.
    ImportD1 {
        #[arg(long)]
        dump: PathBuf,
    },
    /// Print counts per table (sanity check after an import).
    Counts,
    /// Import `scripts/dump-coordinators.ts` output (DO kv per world): seeds
    /// `coordinator_kv`, raises `lastEpoch` to at least the mirror/world
    /// epoch, flags live runtimes with a token amnesty for their epoch, and
    /// arms an immediate alarm so every imported world resolves on boot.
    ImportCoordinators {
        #[arg(long)]
        dump: PathBuf,
    },
    /// Generate a tokens-at-rest master key (base64) for `master_key_file`.
    MasterKeyGen,
    /// Encrypt plaintext OAuth tokens in `storage_accounts` with the master key.
    EncryptTokens {
        #[arg(long)]
        master_key_file: PathBuf,
    },
    /// Generate lane-D relay keys: prints the box-side config values and the
    /// public key / token key the Cloudflare worker needs.
    RelayKeysGen,
    /// Mint a demo relay token with throwaway keys (cross-language parity test).
    RelayTokenDemo,
    /// Parse a swcore TOML and print which knobs are set (values masked) —
    /// run it after every edit of /etc/sharedworld/swcore.toml.
    ConfigCheck {
        #[arg(long, default_value = "/etc/sharedworld/swcore.toml")]
        config: PathBuf,
    },
    /// Live view: scrape /metrics twice, `interval` seconds apart, and print
    /// per-route request rate, rows read/written per request, socket and
    /// coordinator gauges — "are we doing tons of X" from a terminal.
    Top {
        #[arg(long, default_value = "http://127.0.0.1:9464/metrics")]
        metrics: String,
        #[arg(long, default_value_t = 10)]
        interval: u64,
    },
}

fn open(path: &std::path::Path) -> anyhow::Result<Db> {
    Db::open(DbOptions { path: Some(path.to_path_buf()), readers: 1, busy_timeout_ms: 5_000 })
        .map_err(|e| anyhow::anyhow!("{e}"))
}

const STATS_SQL: &[(&str, &str)] = &[
    ("users: total", "SELECT COUNT(*) FROM users"),
    ("users: new last 24h", "SELECT COUNT(*) FROM users WHERE created_at >= strftime('%Y-%m-%dT%H:%M:%S','now','-1 day')"),
    ("users: new last 7d", "SELECT COUNT(*) FROM users WHERE created_at >= strftime('%Y-%m-%dT%H:%M:%S','now','-7 days')"),
    ("users: with linked Drive", "SELECT COUNT(DISTINCT owner_player_uuid) FROM storage_accounts"),
    ("worlds: total (not deleted)", "SELECT COUNT(*) FROM worlds WHERE deleted_at IS NULL"),
    ("worlds: created last 7d", "SELECT COUNT(*) FROM worlds WHERE created_at >= strftime('%Y-%m-%dT%H:%M:%S','now','-7 days')"),
    ("worlds: deleted (all time)", "SELECT COUNT(*) FROM worlds WHERE deleted_at IS NOT NULL"),
    ("worlds: hosted last 24h", "SELECT COUNT(*) FROM world_runtime_mirror WHERE updated_at >= strftime('%Y-%m-%dT%H:%M:%S','now','-1 day')"),
    (
        "worlds: live right now",
        "SELECT COUNT(*) FROM world_runtime_mirror WHERE updated_at >= strftime('%Y-%m-%dT%H:%M:%S','now','-5 minutes') AND json_extract(status_json,'$.phase') IN ('host-starting','host-live','host-finalizing')",
    ),
    (
        "players in live worlds right now",
        "SELECT COALESCE(SUM(json_array_length(COALESCE(room_players_json,'[]'))),0) FROM world_runtime_mirror WHERE updated_at >= strftime('%Y-%m-%dT%H:%M:%S','now','-5 minutes') AND json_extract(status_json,'$.phase') IN ('host-starting','host-live','host-finalizing')",
    ),
    (
        "DAU (lower bound)",
        "SELECT COUNT(*) FROM (SELECT player_uuid FROM user_sessions WHERE created_at >= strftime('%Y-%m-%dT%H:%M:%S','now','-1 day') UNION SELECT created_by_uuid FROM snapshots WHERE created_at >= strftime('%Y-%m-%dT%H:%M:%S','now','-1 day') UNION SELECT player_uuid FROM world_memberships WHERE joined_at >= strftime('%Y-%m-%dT%H:%M:%S','now','-1 day'))",
    ),
    (
        "WAU (lower bound)",
        "SELECT COUNT(*) FROM (SELECT player_uuid FROM user_sessions WHERE created_at >= strftime('%Y-%m-%dT%H:%M:%S','now','-7 days') UNION SELECT created_by_uuid FROM snapshots WHERE created_at >= strftime('%Y-%m-%dT%H:%M:%S','now','-7 days') UNION SELECT player_uuid FROM world_memberships WHERE joined_at >= strftime('%Y-%m-%dT%H:%M:%S','now','-7 days'))",
    ),
    ("fresh logins last 24h (distinct players)", "SELECT COUNT(DISTINCT player_uuid) FROM user_sessions WHERE created_at >= strftime('%Y-%m-%dT%H:%M:%S','now','-1 day')"),
    ("snapshot uploads last 24h", "SELECT COUNT(*) FROM snapshots WHERE created_at >= strftime('%Y-%m-%dT%H:%M:%S','now','-1 day')"),
    ("uploading hosts last 24h (distinct)", "SELECT COUNT(DISTINCT created_by_uuid) FROM snapshots WHERE created_at >= strftime('%Y-%m-%dT%H:%M:%S','now','-1 day')"),
    ("memberships: active (world,player) pairs", "SELECT COUNT(*) FROM world_memberships WHERE deleted_at IS NULL"),
    ("storage: blobs tracked on Drive", "SELECT COUNT(*) FROM storage_objects"),
    ("storage: GB on Drive (all accounts)", "SELECT ROUND(COALESCE(SUM(size),0)/1e9, 2) FROM storage_objects"),
    ("storage: GB added last 24h", "SELECT ROUND(COALESCE(SUM(size),0)/1e9, 2) FROM storage_objects WHERE created_at >= strftime('%Y-%m-%dT%H:%M:%S','now','-1 day')"),
    ("storage: GB added last 7d", "SELECT ROUND(COALESCE(SUM(size),0)/1e9, 2) FROM storage_objects WHERE created_at >= strftime('%Y-%m-%dT%H:%M:%S','now','-7 days')"),
    ("storage: accounts holding blobs", "SELECT COUNT(DISTINCT storage_account_id) FROM storage_objects"),
    ("storage: GB per account holding blobs", "SELECT ROUND(COALESCE(SUM(size),0)/1e9 / MAX(1, COUNT(DISTINCT storage_account_id)), 2) FROM storage_objects"),
    ("storage: GB per world (not deleted)", "SELECT ROUND((SELECT COALESCE(SUM(size),0) FROM storage_objects)/1e9 / MAX(1, (SELECT COUNT(*) FROM worlds WHERE deleted_at IS NULL)), 2)"),
    ("storage: blob deletes pending", "SELECT COUNT(*) FROM pending_blob_deletes"),
    ("coordinator: worlds with state", "SELECT COUNT(DISTINCT world_id) FROM coordinator_kv"),
    ("coordinator: alarms armed", "SELECT COUNT(*) FROM coordinator_alarms"),
];

fn stats(db: &Db) -> anyhow::Result<()> {
    let rows: Vec<(String, String)> = db
        .read_blocking(|c| {
            let mut out = Vec::new();
            for (label, sql) in STATS_SQL {
                let v: rusqlite::types::Value = c.raw().query_row(sql, [], |r| r.get(0))?;
                out.push((label.to_string(), fmt_value(&v)));
            }
            // storage: GB by kind
            let mut stmt = c.raw().prepare(
                "SELECT 'storage: GB by kind ' || substr(storage_key, 1, instr(storage_key, '/') - 1), ROUND(SUM(size)/1e9, 2) FROM storage_objects GROUP BY 1 ORDER BY SUM(size) DESC",
            )?;
            let kinds = stmt.query_map([], |r| Ok((r.get::<_, String>(0)?, fmt_value(&r.get::<_, rusqlite::types::Value>(1)?))))?;
            for k in kinds {
                out.push(k?);
            }
            Ok(out)
        })
        .map_err(|e| anyhow::anyhow!("{e}"))?;
    let width = rows.iter().map(|(m, _)| m.len()).max().unwrap_or(0);
    for (m, v) in rows {
        println!("{m:<width$}  {v}");
    }
    Ok(())
}

fn fmt_value(v: &rusqlite::types::Value) -> String {
    match v {
        rusqlite::types::Value::Null => "null".into(),
        rusqlite::types::Value::Integer(i) => i.to_string(),
        rusqlite::types::Value::Real(f) => format!("{f}"),
        rusqlite::types::Value::Text(s) => s.clone(),
        rusqlite::types::Value::Blob(b) => format!("<{} bytes>", b.len()),
    }
}

fn counts(db: &Db) -> anyhow::Result<()> {
    let rows: Vec<(String, i64)> = db
        .read_blocking(|c| {
            let names: Vec<String> = c
                .raw()
                .prepare("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")?
                .query_map([], |r| r.get(0))?
                .collect::<Result<_, _>>()?;
            let mut out = Vec::new();
            for n in names {
                let count: i64 = c.raw().query_row(&format!("SELECT COUNT(*) FROM \"{n}\""), [], |r| r.get(0))?;
                out.push((n, count));
            }
            Ok(out)
        })
        .map_err(|e| anyhow::anyhow!("{e}"))?;
    for (n, c) in rows {
        println!("{n:<32} {c}");
    }
    Ok(())
}

fn import_d1(db_path: &std::path::Path, dump: &std::path::Path) -> anyhow::Result<()> {
    if db_path.exists() {
        anyhow::bail!("{} already exists; import into a fresh path", db_path.display());
    }
    let sql = std::fs::read_to_string(dump).with_context(|| format!("reading {}", dump.display()))?;
    // D1 exports carry its own `d1_migrations` table; harmless. Foreign keys
    // are off during the bulk load (row order in dumps is not FK-sorted).
    //
    // Statements are split here and prepared one by one: handing SQLite the
    // whole multi-hundred-MB dump makes every prepare copy the remaining text
    // (quadratic — a 280 MB export took >45 min that way). One transaction
    // with journaling/sync off on a private connection (the pool's readers
    // would block the journal-mode change) turns it into a sub-minute bulk
    // load; the pool then reopens it in WAL as usual.
    let started = std::time::Instant::now();
    let total = {
        let conn = rusqlite::Connection::open(db_path)?;
        conn.execute_batch(
            "PRAGMA foreign_keys = OFF; PRAGMA journal_mode = OFF; PRAGMA synchronous = OFF;",
        )?;
        conn.execute_batch("BEGIN;")?;
        let mut n = 0usize;
        for stmt in split_sql_statements(&sql) {
            let trimmed = stmt.trim();
            if trimmed.is_empty() || trimmed.starts_with("--") {
                continue;
            }
            let upper = trimmed.get(..6).map(|p| p.to_ascii_uppercase()).unwrap_or_default();
            if upper.starts_with("BEGIN") || upper.starts_with("COMMIT") {
                continue; // the whole load is one transaction
            }
            conn.execute_batch(trimmed).with_context(|| format!("statement {}: {}", n + 1, head(trimmed)))?;
            n += 1;
        }
        conn.execute_batch("COMMIT;")?;
        conn.execute_batch("PRAGMA journal_mode = WAL; PRAGMA synchronous = NORMAL;")?;
        conn.close().map_err(|(_, e)| e)?;
        n
    };
    let db = open(db_path)?;
    println!("loaded {total} statements in {:.1}s", started.elapsed().as_secs_f64());
    let marked = migrate::mark_all_applied(&db, Some("0029_zzz")).map_err(|e| anyhow::anyhow!("{e}"))?;
    let applied = migrate::migrate(&db).map_err(|e| anyhow::anyhow!("{e}"))?;
    println!("imported {}; marked {marked} worker migrations applied; applied {:?}", dump.display(), applied);
    counts(&db)
}

fn head(s: &str) -> String {
    s.chars().take(80).collect()
}

/// Split a SQL script into statements on `;` outside string literals
/// (`'…'` with `''` escapes; `"…"` identifiers). Enough for sqlite/D1 dumps,
/// which have no comments or triggers.
fn split_sql_statements(sql: &str) -> impl Iterator<Item = &str> {
    let bytes = sql.as_bytes();
    let mut out = Vec::new();
    let (mut start, mut in_single, mut in_double) = (0usize, false, false);
    for (i, &b) in bytes.iter().enumerate() {
        match b {
            b'\'' if !in_double => in_single = !in_single,
            b'"' if !in_single => in_double = !in_double,
            b';' if !in_single && !in_double => {
                out.push(&sql[start..i]);
                start = i + 1;
            }
            _ => {}
        }
    }
    if start < sql.len() {
        out.push(&sql[start..]);
    }
    out.into_iter()
}

fn import_coordinators(db_path: &std::path::Path, dump: &std::path::Path) -> anyhow::Result<()> {
    let db = open(db_path)?;
    migrate::migrate(&db).map_err(|e| anyhow::anyhow!("{e}"))?;
    let text = std::fs::read_to_string(dump).with_context(|| format!("reading {}", dump.display()))?;
    let repo = sw_db::Repository::new(db.clone(), None);
    let rt = tokio::runtime::Runtime::new()?;
    let (mut imported, mut live, mut raised) = (0usize, 0usize, 0usize);
    for (lineno, line) in text.lines().enumerate() {
        if line.trim().is_empty() {
            continue;
        }
        let entry: serde_json::Value =
            serde_json::from_str(line).with_context(|| format!("line {}", lineno + 1))?;
        let world_id = entry["worldId"]
            .as_str()
            .ok_or_else(|| anyhow::anyhow!("line {}: worldId missing", lineno + 1))?
            .to_string();
        let mut kv: std::collections::BTreeMap<String, String> = entry["kv"]
            .as_object()
            .map(|o| o.iter().filter_map(|(k, v)| v.as_str().map(|s| (k.clone(), s.to_string()))).collect())
            .unwrap_or_default();
        // Epoch high-water mark: never below what D1 knows.
        let mirror_epoch: i64 = rt
            .block_on(repo.get_runtime_mirror(&world_id))?
            .and_then(|m| m.status_json)
            .and_then(|s| serde_json::from_str::<serde_json::Value>(&s).ok())
            .and_then(|v| v["runtimeEpoch"].as_i64())
            .unwrap_or(0);
        let world_epoch: i64 = db
            .read_blocking({
                let w = world_id.clone();
                move |c| {
                    Ok(c.raw()
                        .query_row("SELECT last_runtime_epoch FROM worlds WHERE id = ?", [w], |r| {
                            r.get::<_, i64>(0)
                        })
                        .unwrap_or(0))
                }
            })
            .map_err(|e| anyhow::anyhow!("{e}"))?;
        let dumped_last: i64 = kv.get("lastEpoch").and_then(|v| v.parse().ok()).unwrap_or(0);
        // lastEpoch is the RETIRED high-water mark: with a live runtime it is
        // at most epoch-1 (a live epoch must not look released); without one
        // it must cover whatever the mirror/world row last saw.
        let live_epoch = kv
            .get("runtime")
            .and_then(|r| serde_json::from_str::<serde_json::Value>(r).ok())
            .and_then(|v| v["runtimeEpoch"].as_i64());
        let floor = match live_epoch {
            Some(e) => world_epoch.max(e - 1).max(mirror_epoch.min(e - 1)),
            None => mirror_epoch.max(world_epoch),
        };
        if dumped_last < floor {
            kv.insert("lastEpoch".into(), floor.to_string());
            raised += 1;
        }
        if let Some(runtime) = kv.get("runtime").cloned() {
            if let Ok(mut v) = serde_json::from_str::<serde_json::Value>(&runtime) {
                if v.is_object() {
                    // The host keeps presenting its old token for the life of
                    // this epoch; accept it until the runtime retires.
                    v["tokenAmnesty"] = serde_json::Value::Bool(true);
                    // Cutover grace: the maintenance window ate the host's
                    // heartbeats, so every live deadline is measured from the
                    // box's clock, not the worker's last sighting — otherwise
                    // the first alarm would expire a host that is merely
                    // waiting for the new address. Hosts get a full lease
                    // (150 s) to reconnect; a host that never comes back
                    // expires after exactly that, as it would have anyway.
                    let now = sw_db::time::now();
                    let lease = sw_db::time::plus_ms_iso(now, sw_contracts::HOST_LIVE_LEASE_TIMEOUT_MS);
                    match v["phase"].as_str() {
                        Some("host-live") => v["expiresAt"] = serde_json::Value::String(lease.clone()),
                        Some("host-starting") => {
                            v["startupDeadlineAt"] = serde_json::Value::String(lease.clone())
                        }
                        Some("host-finalizing") => {
                            v["lastProgressAt"] = serde_json::Value::String(sw_db::time::to_iso(now))
                        }
                        _ => {}
                    }
                    kv.insert("runtime".into(), v.to_string());
                    // Socket-loss grace restarts too: the link is down until
                    // the host's socket arrives at the box.
                    kv.insert(
                        "hostLink".into(),
                        serde_json::json!({ "connected": false, "graceDeadlineAt": lease }).to_string(),
                    );
                    live += 1;
                }
            }
        }
        let rows: Vec<(String, String)> = kv.into_iter().collect();
        rt.block_on(repo.coordinator_kv_replace(&world_id, rows))?;
        // Resolve on boot: an immediate alarm re-derives deadlines/election.
        rt.block_on(repo.coordinator_flush(
            &world_id,
            false,
            vec![],
            Some(Some(sw_db::time::plus_ms_iso(sw_db::time::now(), 1_000))),
        ))?;
        imported += 1;
    }
    println!("imported {imported} coordinators ({live} live runtimes with token amnesty, {raised} lastEpoch raised)");
    Ok(())
}

fn main() -> anyhow::Result<()> {
    let args = Args::parse();
    match args.cmd {
        Cmd::Migrate => {
            let db = open(&args.db)?;
            let applied = migrate::migrate(&db).map_err(|e| anyhow::anyhow!("{e}"))?;
            match applied.last() {
                Some(last) => println!("applied {} migration(s), last {last}", applied.len()),
                None => println!("database is up to date"),
            }
        }
        Cmd::Stats => stats(&open(&args.db)?)?,
        Cmd::Counts => counts(&open(&args.db)?)?,
        Cmd::ImportD1 { dump } => import_d1(&args.db, &dump)?,
        Cmd::ImportCoordinators { dump } => import_coordinators(&args.db, &dump)?,
        Cmd::MasterKeyGen => println!("{}", sw_db::TokenCipher::generate_key_b64()),
        Cmd::EncryptTokens { master_key_file } => {
            let cipher = sw_db::TokenCipher::from_key_file(&master_key_file)?;
            let db = open(&args.db)?;
            let n = db
                .write_blocking(move |c| sw_db::repo::storage::encrypt_plaintext_tokens(c, &cipher))
                .map_err(|e| anyhow::anyhow!("{e}"))?;
            println!("encrypted tokens on {n} account row(s)");
        }
        Cmd::RelayKeysGen => {
            let (signing, token_key, public) = sw_core::relay::RelayKeys::generate();
            println!("# swcore.toml");
            println!("relay_signing_key_b64 = \"{signing}\"");
            println!("relay_token_key_b64 = \"{token_key}\"");
            println!("# wrangler secrets (lane-d worker)");
            println!("RELAY_PUBLIC_KEY = \"{public}\"");
            println!("RELAY_TOKEN_KEY = \"{token_key}\"");
        }
        Cmd::Top { metrics, interval } => top(&metrics, interval)?,
        Cmd::ConfigCheck { config } => config_check(&config)?,
        Cmd::RelayTokenDemo => {
            let (signing, token_key, public) = sw_core::relay::RelayKeys::generate();
            let keys = sw_core::relay::RelayKeys::from_config(&signing, &token_key)
                .map_err(|e| anyhow::anyhow!(e))?;
            let exp = sw_db::time::to_millis(sw_db::time::now()) + 3_600_000;
            let token = keys.mint(
                "world-demo",
                "packs/full/ab/abc.pack",
                "acct-1",
                "file-123",
                "player-1",
                "ya29.demo-access-token",
                exp,
            );
            println!(
                "{}",
                serde_json::json!({"publicKey": public, "tokenKey": token_key, "token": token, "fileId": "file-123", "accessToken": "ya29.demo-access-token", "exp": exp})
            );
        }
    }
    Ok(())
}

// ---------------------------------------------------------------------------
// top
// ---------------------------------------------------------------------------

fn scrape_metrics(url: &str) -> anyhow::Result<std::collections::BTreeMap<String, f64>> {
    let text = reqwest::blocking::get(url)?.text()?;
    let mut out = std::collections::BTreeMap::new();
    for line in text.lines() {
        if line.starts_with('#') || line.is_empty() {
            continue;
        }
        if let Some((k, v)) = line.rsplit_once(' ') {
            if let Ok(v) = v.parse::<f64>() {
                out.insert(k.to_string(), v);
            }
        }
    }
    Ok(out)
}

fn label(series: &str, name: &str) -> Option<String> {
    let pat = format!("{name}=\"");
    let start = series.find(&pat)? + pat.len();
    let end = series[start..].find('"')? + start;
    Some(series[start..end].to_string())
}

fn top(url: &str, interval: u64) -> anyhow::Result<()> {
    use std::collections::BTreeMap;
    let a = scrape_metrics(url)?;
    std::thread::sleep(std::time::Duration::from_secs(interval.max(1)));
    let b = scrape_metrics(url)?;
    let secs = interval.max(1) as f64;
    let delta = |prefix: &str, by: &str| -> BTreeMap<String, f64> {
        let mut m = BTreeMap::new();
        for (k, v) in &b {
            if !k.starts_with(prefix) {
                continue;
            }
            let d = v - a.get(k).copied().unwrap_or(0.0);
            if let Some(l) = label(k, by) {
                *m.entry(l).or_insert(0.0) += d;
            }
        }
        m
    };
    let reqs = delta("http_requests_total", "route");
    let rows_r = delta("db_route_rows_returned_total", "route");
    let rows_w = delta("db_route_rows_changed_total", "route");
    let errs: f64 = b
        .iter()
        .filter(|(k, _)| {
            k.starts_with("http_requests_total") && label(k, "status").is_some_and(|s| s.starts_with('5'))
        })
        .map(|(k, v)| v - a.get(k).copied().unwrap_or(0.0))
        .sum();
    let total: f64 = reqs.values().sum();
    println!("{}s window · {:.1} req/s · {:.0} 5xx", interval, total / secs, errs.max(0.0));
    println!("{:<52}{:>8}{:>10}{:>10}", "route", "req/s", "rows r/rq", "rows w/rq");
    let mut routes: Vec<(&String, &f64)> = reqs.iter().filter(|(_, v)| **v > 0.0).collect();
    routes.sort_by(|x, y| y.1.partial_cmp(x.1).unwrap());
    for (route, n) in routes {
        println!(
            "{:<52}{:>8.1}{:>10.1}{:>10.1}",
            route,
            n / secs,
            rows_r.get(route).copied().unwrap_or(0.0) / n,
            rows_w.get(route).copied().unwrap_or(0.0) / n
        );
    }
    let stmts = delta("db_stmt_total", "name");
    let mut top_stmts: Vec<(&String, &f64)> = stmts.iter().filter(|(_, v)| **v > 0.0).collect();
    top_stmts.sort_by(|x, y| y.1.partial_cmp(x.1).unwrap());
    println!("\nstatements/s (top 10):");
    for (name, n) in top_stmts.into_iter().take(10) {
        println!("  {:>8.1}  {}", n / secs, name);
    }
    let scans = delta("db_fullscan_steps_total", "name");
    let scanning: Vec<String> =
        scans.iter().filter(|(_, v)| **v > 0.0).map(|(k, v)| format!("{k} ({v:.0})")).collect();
    println!(
        "full-scan steps: {}",
        if scanning.is_empty() { "none".to_string() } else { scanning.join(", ") }
    );
    for key in [
        "ws_connections",
        "coordinator_worlds_loaded",
        "db_write_queue_depth",
        "pending_blob_deletes_depth",
        "process_resident_memory_bytes",
        "process_open_fds",
    ] {
        if let Some(v) = b.get(key) {
            println!("{key} = {v}");
        }
    }
    Ok(())
}

fn config_check(path: &std::path::Path) -> anyhow::Result<()> {
    let text = std::fs::read_to_string(path).with_context(|| format!("reading {}", path.display()))?;
    let config: sw_core::Config = toml::from_str(&text)
        .with_context(|| format!("{} does not parse as swcore config", path.display()))?;
    let mask = |v: &Option<String>| match v {
        Some(s) if !s.is_empty() => format!("set ({} chars)", s.chars().count()),
        _ => "UNSET".to_string(),
    };
    println!("active_storage_provider = {:?}", config.active_storage_provider);
    println!("public_base_url         = {}", config.public_base_url.as_deref().unwrap_or("UNSET"));
    println!("relay_base_url          = {}", config.relay_base_url.as_deref().unwrap_or("UNSET"));
    println!("signing_secret          = {}", mask(&config.signing_secret));
    println!("signing_secret_previous = {}", mask(&config.signing_secret_previous));
    println!("google_oauth_client_id  = {}", mask(&config.google_oauth_client_id));
    println!("google_oauth_client_sec = {}", mask(&config.google_oauth_client_secret));
    println!("google_oauth_redirect   = {}", config.google_oauth_redirect_uri.as_deref().unwrap_or("UNSET"));
    println!("internal_api_secret     = {}", mask(&config.internal_api_secret));
    println!("relay_signing_key_b64   = {}", mask(&config.relay_signing_key_b64));
    println!("relay_token_key_b64     = {}", mask(&config.relay_token_key_b64));
    println!("master_key_file         = {:?}", config.master_key_file);
    println!(
        "allow_dev_auth={} allow_dev_google_oauth={} test_routes={}",
        config.allow_dev_auth, config.allow_dev_google_oauth, config.test_routes
    );
    let mut problems = Vec::new();
    if config.relay_signing_key_b64.is_some() != config.relay_token_key_b64.is_some() {
        problems.push("only one relay key is set");
    }
    if config.relay_base_url.is_some() && config.relay_signing_key_b64.is_none() {
        problems.push("relay_base_url without relay keys");
    }
    if let (Some(s), Some(t)) = (&config.relay_signing_key_b64, &config.relay_token_key_b64) {
        if let Err(e) = sw_core::relay::RelayKeys::from_config(s, t) {
            problems.push(Box::leak(format!("relay keys invalid: {e}").into_boxed_str()));
        }
    }
    if config.allow_dev_auth || config.test_routes {
        problems.push("dev auth / test routes enabled (never in production)");
    }
    if problems.is_empty() {
        println!("OK");
        Ok(())
    } else {
        for p in &problems {
            println!("PROBLEM: {p}");
        }
        anyhow::bail!("{} problem(s)", problems.len())
    }
}

#[cfg(test)]
mod split_tests {
    use super::split_sql_statements;

    #[test]
    fn semicolons_inside_strings_do_not_split() {
        let sql = "INSERT INTO t VALUES ('a;b', 'it''s; fine');\nINSERT INTO \"x;y\" VALUES (1);\n";
        let v: Vec<&str> = split_sql_statements(sql).map(str::trim).filter(|s| !s.is_empty()).collect();
        assert_eq!(v.len(), 2, "{v:?}");
        assert!(v[0].ends_with("'it''s; fine')"));
        assert!(v[1].starts_with("INSERT INTO \"x;y\""));
    }
}
