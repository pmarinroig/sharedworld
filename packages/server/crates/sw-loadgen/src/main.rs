//! Load generator for the Rust server: `--worlds` worlds, each with a host and
//! `--guests` guests, driven at the real protocol cadences divided by
//! `--time-scale` (10 → a 30 s heartbeat every 3 s). Dev auth only — point it
//! at `swcore --dev` (any storage provider; blobs are tiny).
//!
//! Per world: host logs in, creates the world + invite, uploads a first
//! snapshot (prepare → PUT → finalize), heartbeats to host-live; guests redeem,
//! enter, open a WebSocket with presence, beat presence; the host autosaves
//! (a changed file → prepare → PUT → finalize) and guests, woken by the
//! `snapshot-changed` event, ask for a download plan and fetch the blob.
//! At the end: per-operation latency percentiles, request rate, errors, and a
//! scrape of the server's `/metrics` (rows read/written per route, RSS).

use std::collections::BTreeMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use anyhow::{anyhow, Context, Result};
use clap::Parser;
use futures_util::{SinkExt, StreamExt};
use rand::RngExt;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use tokio_tungstenite::tungstenite::Message;

#[derive(Parser, Debug, Clone)]
#[command(name = "sw-loadgen")]
struct Args {
    /// swcore base URL (dev mode).
    #[arg(long, default_value = "http://127.0.0.1:8787")]
    base: String,
    /// swcore metrics URL (scraped before/after).
    #[arg(long, default_value = "http://127.0.0.1:9464/metrics")]
    metrics: String,
    #[arg(long, default_value_t = 20)]
    worlds: usize,
    #[arg(long, default_value_t = 2)]
    guests: usize,
    /// Run length in seconds (after the ramp).
    #[arg(long, default_value_t = 60)]
    duration: u64,
    /// Divide every protocol cadence by this (1 = real time).
    #[arg(long, default_value_t = 10.0)]
    time_scale: f64,
    /// Dev auth secret (`allow_dev_auth` on the server).
    #[arg(long, default_value = "dev-secret")]
    dev_secret: String,
    /// Worlds started per second during the ramp.
    #[arg(long, default_value_t = 10.0)]
    ramp_per_sec: f64,
    /// Client version header sent to the server.
    #[arg(long, default_value = "0.4.6")]
    client_version: String,
    /// Snapshot blob size in bytes.
    #[arg(long, default_value_t = 64 * 1024)]
    blob_bytes: usize,
}

// ---------------------------------------------------------------------------
// Stats
// ---------------------------------------------------------------------------

#[derive(Default)]
struct Stats {
    lat: Mutex<BTreeMap<&'static str, Vec<f64>>>,
    errors: Mutex<BTreeMap<String, u64>>,
    requests: AtomicU64,
    ws_events: AtomicU64,
}

impl Stats {
    fn record(&self, op: &'static str, started: Instant) {
        self.requests.fetch_add(1, Ordering::Relaxed);
        self.lat.lock().unwrap().entry(op).or_default().push(started.elapsed().as_secs_f64() * 1000.0);
    }
    fn error(&self, op: &str, what: impl std::fmt::Display) {
        let key = format!("{op}: {what}");
        *self.errors.lock().unwrap().entry(key).or_insert(0) += 1;
    }
    fn report(&self, wall: Duration) {
        println!("\n== latency (ms) ==");
        println!("{:<28}{:>8}{:>9}{:>9}{:>9}{:>9}", "op", "n", "p50", "p95", "p99", "max");
        let lat = self.lat.lock().unwrap();
        for (op, v) in lat.iter() {
            let mut v = v.clone();
            v.sort_by(|a, b| a.partial_cmp(b).unwrap());
            let p = |q: f64| v[((v.len() as f64 - 1.0) * q) as usize];
            println!(
                "{:<28}{:>8}{:>9.1}{:>9.1}{:>9.1}{:>9.1}",
                op,
                v.len(),
                p(0.5),
                p(0.95),
                p(0.99),
                v[v.len() - 1]
            );
        }
        let n = self.requests.load(Ordering::Relaxed);
        println!(
            "\nrequests: {n} ({:.1}/s over {:.0}s), ws events received: {}",
            n as f64 / wall.as_secs_f64(),
            wall.as_secs_f64(),
            self.ws_events.load(Ordering::Relaxed)
        );
        let errors = self.errors.lock().unwrap();
        if errors.is_empty() {
            println!("errors: none");
        } else {
            println!("errors:");
            for (k, v) in errors.iter() {
                println!("  {v:>6}  {k}");
            }
        }
    }
}

// ---------------------------------------------------------------------------
// API client
// ---------------------------------------------------------------------------

#[derive(Clone)]
struct Api {
    http: reqwest::Client,
    base: String,
    version: String,
    stats: Arc<Stats>,
}

impl Api {
    async fn call(
        &self,
        op: &'static str,
        method: reqwest::Method,
        path: &str,
        token: Option<&str>,
        body: Option<Value>,
    ) -> Result<Value> {
        let started = Instant::now();
        let mut req = self
            .http
            .request(method, format!("{}{}", self.base, path))
            .header("x-sharedworld-version", &self.version);
        if let Some(t) = token {
            req = req.bearer_auth(t);
        }
        if let Some(b) = body {
            req = req.json(&b);
        }
        let res = req.send().await;
        let res = match res {
            Ok(r) => r,
            Err(e) => {
                self.stats.error(op, format!("transport {e}"));
                return Err(e.into());
            }
        };
        self.stats.record(op, started);
        let status = res.status().as_u16();
        let text = res.text().await.unwrap_or_default();
        if status >= 400 {
            let v: Value = serde_json::from_str(&text).unwrap_or(Value::Null);
            let code = v["error"].as_str().unwrap_or("?").to_string();
            self.stats.error(op, format!("{status} {code}"));
            return Err(anyhow!("{op}: {status} {code}"));
        }
        if text.is_empty() {
            return Ok(Value::Null);
        }
        Ok(serde_json::from_str(&text).unwrap_or(Value::Null))
    }

    async fn dev_login(&self, secret: &str, uuid: &str, name: &str) -> Result<String> {
        let v = self
            .call(
                "auth.dev-complete",
                reqwest::Method::POST,
                "/auth/dev-complete",
                None,
                Some(json!({"playerUuid": uuid, "playerName": name, "secret": secret})),
            )
            .await?;
        Ok(v["token"].as_str().context("token")?.to_string())
    }

    /// PUT a blob through a `SignedBlobUrl` (relay upload), bearer included
    /// because the URL origin is the server itself.
    async fn put_signed(&self, op: &'static str, token: &str, signed: &Value, bytes: Vec<u8>) -> Result<()> {
        let started = Instant::now();
        let mut req = self.http.put(signed["url"].as_str().context("url")?).bearer_auth(token);
        if let Some(h) = signed["headers"].as_object() {
            for (k, v) in h {
                req = req.header(k.as_str(), v.as_str().unwrap_or(""));
            }
        }
        let res = req.header("content-type", "application/octet-stream").body(bytes).send().await?;
        self.stats.record(op, started);
        if !res.status().is_success() {
            let status = res.status().as_u16();
            self.stats.error(op, format!("{status}"));
            return Err(anyhow!("{op}: {status}"));
        }
        Ok(())
    }

    async fn get_signed(&self, op: &'static str, token: &str, signed: &Value) -> Result<usize> {
        let started = Instant::now();
        let mut req = self.http.get(signed["url"].as_str().context("url")?).bearer_auth(token);
        if let Some(h) = signed["headers"].as_object() {
            for (k, v) in h {
                req = req.header(k.as_str(), v.as_str().unwrap_or(""));
            }
        }
        let res = req.send().await?;
        let status = res.status().as_u16();
        let bytes = res.bytes().await?;
        self.stats.record(op, started);
        if status >= 400 {
            self.stats.error(op, format!("{status}"));
            return Err(anyhow!("{op}: {status}"));
        }
        Ok(bytes.len())
    }
}

fn sha256_hex(bytes: &[u8]) -> String {
    let mut h = Sha256::new();
    h.update(bytes);
    format!("{:x}", h.finalize())
}

fn scaled(ms: u64, scale: f64) -> Duration {
    Duration::from_millis(((ms as f64) / scale).max(50.0) as u64)
}

// ---------------------------------------------------------------------------
// World scenario
// ---------------------------------------------------------------------------

struct Snapshot {
    level_bytes: Vec<u8>,
}

async fn autosave(
    api: &Api,
    token: &str,
    world_id: &str,
    epoch: i64,
    host_token: &str,
    snap: &Snapshot,
    base_snapshot_id: Option<&str>,
) -> Result<String> {
    // The modern (0.2+) shape: every non-region file travels in the single
    // "non-region" pack; the pack blob is what gets uploaded.
    let hash = sha256_hex(&snap.level_bytes);
    let member = json!({"path": "level.dat", "hash": hash, "size": snap.level_bytes.len(), "contentType": "application/octet-stream"});
    let pack_desc = json!({"packId": "non-region", "hash": hash, "size": snap.level_bytes.len(), "fileCount": 1, "files": [member]});
    let plan = api
        .call(
            "uploads.prepare",
            reqwest::Method::POST,
            &format!("/worlds/{world_id}/uploads/prepare"),
            Some(token),
            Some(json!({"runtimeEpoch": epoch, "hostToken": host_token, "files": [], "nonRegionPack": pack_desc, "regionBundles": []})),
        )
        .await?;
    let entry = &plan["nonRegionPackUpload"];
    if entry.is_null() {
        return Err(anyhow!("prepare returned no nonRegionPackUpload"));
    }
    // Always upload the full pack (a delta would need the base artifact on
    // this side; the server accepts a full even when a delta was offered).
    let storage_key = entry["fullStorageKey"]
        .as_str()
        .or(entry["storageKey"].as_str())
        .context("pack storageKey")?
        .to_string();
    if entry["alreadyPresent"] != true {
        let signed = if entry["fullUpload"].is_object() {
            entry["fullUpload"].clone()
        } else {
            entry["upload"].clone()
        };
        api.put_signed("blob.put", token, &signed, snap.level_bytes.clone()).await?;
    }
    let manifest = api
        .call(
            "uploads.finalize",
            reqwest::Method::POST,
            &format!("/worlds/{world_id}/uploads/finalize-snapshot"),
            Some(token),
            Some(json!({
                "runtimeEpoch": epoch, "hostToken": host_token, "baseSnapshotId": base_snapshot_id,
                "dataVersion": 4325, "minecraftVersion": "1.21.11",
                "files": [],
                "packs": [{
                    "packId": "non-region", "hash": hash, "size": snap.level_bytes.len(), "storageKey": storage_key,
                    "transferMode": "pack-full", "chainDepth": 0, "files": [member]
                }]
            })),
        )
        .await?;
    Ok(manifest["snapshotId"].as_str().unwrap_or("").to_string())
}

async fn run_guest(
    api: Api,
    args: Args,
    world_id: String,
    idx: usize,
    stop: tokio_util::sync::CancellationToken,
    invite_code: String,
) -> Result<()> {
    let uuid = uuid::Uuid::new_v4().simple().to_string();
    let token = api.dev_login(&args.dev_secret, &uuid, &format!("Guest{idx}")).await?;
    api.call(
        "invites.redeem",
        reqwest::Method::POST,
        "/invites/redeem",
        Some(&token),
        Some(json!({"code": invite_code})),
    )
    .await?;
    api.call(
        "session.enter",
        reqwest::Method::POST,
        &format!("/worlds/{world_id}/session/enter"),
        Some(&token),
        Some(json!({})),
    )
    .await?;
    // Socket with presence.
    let ws_url = format!("{}/ws", api.base.replacen("http", "ws", 1));
    let mut req =
        tokio_tungstenite::tungstenite::client::IntoClientRequest::into_client_request(ws_url.as_str())?;
    req.headers_mut().insert("authorization", format!("Bearer {token}").parse()?);
    let (mut ws, _) = tokio_tungstenite::connect_async(req).await.context("ws connect")?;
    ws.send(Message::Text(
        json!({"v":1,"type":"world-presence","worldId":world_id,"present":true}).to_string().into(),
    ))
    .await?;
    let mut presence_seq = 1i64;
    let mut presence_tick = tokio::time::interval(scaled(15_000, args.time_scale));
    let mut keepalive_tick = tokio::time::interval(scaled(20_000, args.time_scale));
    loop {
        tokio::select! {
            _ = stop.cancelled() => break,
            _ = presence_tick.tick() => {
                presence_seq += 1;
                let _ = api.call("presence", reqwest::Method::POST, &format!("/worlds/{world_id}/presence"), Some(&token),
                    Some(json!({"present": true, "guestSessionEpoch": 1, "presenceSequence": presence_seq}))).await;
            }
            _ = keepalive_tick.tick() => {
                let _ = ws.send(Message::Text("sw-keepalive".into())).await;
            }
            frame = ws.next() => {
                let Some(Ok(frame)) = frame else { break };
                if let Message::Text(text) = frame {
                    if text.as_str() == "sw-keepalive-ack" { continue; }
                    api.stats.ws_events.fetch_add(1, Ordering::Relaxed);
                    let v: Value = serde_json::from_str(&text).unwrap_or(Value::Null);
                    if v["type"] == "event" && v["event"]["kind"] == "snapshot-changed" {
                        // Guest reacts like the mod: plan + fetch.
                        if let Ok(plan) = api.call("downloads.plan", reqwest::Method::POST,
                            &format!("/worlds/{world_id}/downloads/plan"), Some(&token), Some(json!({"files": []}))).await {
                            for d in plan["downloads"].as_array().cloned().unwrap_or_default() {
                                for step in d["steps"].as_array().cloned().unwrap_or_default() {
                                    let _ = api.get_signed("blob.get", &token, &step["download"]).await;
                                }
                            }
                        }
                    } else if v["type"] == "event" && v["event"]["kind"] == "runtime-changed" {
                        let _ = api.call("worlds.get", reqwest::Method::GET, &format!("/worlds/{world_id}"), Some(&token), None).await;
                    }
                }
            }
        }
    }
    let _ = ws.close(None).await;
    Ok(())
}

async fn run_world(
    api: Api,
    args: Args,
    idx: usize,
    stop: tokio_util::sync::CancellationToken,
) -> Result<()> {
    let host_uuid = uuid::Uuid::new_v4().simple().to_string();
    let token = api.dev_login(&args.dev_secret, &host_uuid, &format!("Host{idx}")).await?;
    let world = api
        .call(
            "worlds.create",
            reqwest::Method::POST,
            "/worlds",
            Some(&token),
            Some(json!({"name": format!("Load world {idx}")})),
        )
        .await?;
    let world_id = world["world"]["id"]
        .as_str()
        .or(world["world"]["summary"]["id"].as_str())
        .or(world["id"].as_str())
        .context("world id")?
        .to_string();
    let assignment = world["initialUploadAssignment"].clone();
    let mut epoch = assignment["runtimeEpoch"].as_i64().context("epoch")?;
    let host_token = assignment["hostToken"].as_str().context("hostToken")?.to_string();
    let invite = api
        .call(
            "invites.create",
            reqwest::Method::POST,
            &format!("/worlds/{world_id}/invites"),
            Some(&token),
            Some(json!({})),
        )
        .await?;
    let code = invite["code"].as_str().context("invite code")?.to_string();

    let mut snap = Snapshot { level_bytes: random_bytes(args.blob_bytes) };
    let mut last_snapshot = Some(autosave(&api, &token, &world_id, epoch, &host_token, &snap, None).await?);
    api.call(
        "heartbeat",
        reqwest::Method::POST,
        &format!("/worlds/{world_id}/heartbeat"),
        Some(&token),
        Some(json!({"runtimeEpoch": epoch, "hostToken": host_token, "joinTarget": format!("load{idx}.example:25565"), "minecraftVersion": "1.21.11"})),
    )
    .await?;

    // Guests join.
    let mut guests = Vec::new();
    for g in 0..args.guests {
        let h = tokio::spawn(run_guest(
            api.clone(),
            args.clone(),
            world_id.clone(),
            g,
            stop.clone(),
            code.clone(),
        ));
        guests.push(h);
    }

    let mut hb_tick = tokio::time::interval(scaled(30_000, args.time_scale));
    let mut autosave_tick = tokio::time::interval(scaled(300_000, args.time_scale));
    hb_tick.tick().await;
    autosave_tick.tick().await;
    loop {
        tokio::select! {
            _ = stop.cancelled() => break,
            _ = hb_tick.tick() => {
                if let Ok(v) = api.call("heartbeat", reqwest::Method::POST, &format!("/worlds/{world_id}/heartbeat"), Some(&token),
                    Some(json!({"runtimeEpoch": epoch, "hostToken": host_token, "joinTarget": format!("load{idx}.example:25565")}))).await {
                    if let Some(e) = v["runtimeEpoch"].as_i64() { epoch = e; }
                }
            }
            _ = autosave_tick.tick() => {
                // Mutate a few bytes so the blob is new.
                let n = snap.level_bytes.len();
                let at = rand::rng().random_range(0..n);
                snap.level_bytes[at] = snap.level_bytes[at].wrapping_add(1);
                if let Ok(id) = autosave(&api, &token, &world_id, epoch, &host_token, &snap, last_snapshot.as_deref()).await {
                    last_snapshot = Some(id);
                }
            }
        }
    }
    // Release.
    let _ = api
        .call(
            "release-host",
            reqwest::Method::POST,
            &format!("/worlds/{world_id}/release-host"),
            Some(&token),
            Some(json!({"runtimeEpoch": epoch, "hostToken": host_token})),
        )
        .await;
    for g in guests {
        let _ = g.await;
    }
    Ok(())
}

fn random_bytes(n: usize) -> Vec<u8> {
    let mut v = vec![0u8; n];
    rand::fill(&mut v[..]);
    v
}

// ---------------------------------------------------------------------------
// Metrics scrape
// ---------------------------------------------------------------------------

async fn scrape(http: &reqwest::Client, url: &str) -> BTreeMap<String, f64> {
    let mut out = BTreeMap::new();
    let Ok(res) = http.get(url).send().await else { return out };
    let Ok(text) = res.text().await else { return out };
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
    out
}

fn print_metric_delta(
    before: &BTreeMap<String, f64>,
    after: &BTreeMap<String, f64>,
    prefix: &str,
    top: usize,
) {
    let mut rows: Vec<(String, f64)> = after
        .iter()
        .filter(|(k, _)| k.starts_with(prefix))
        .map(|(k, v)| (k.clone(), v - before.get(k).copied().unwrap_or(0.0)))
        .filter(|(_, d)| *d > 0.0)
        .collect();
    rows.sort_by(|a, b| b.1.partial_cmp(&a.1).unwrap());
    for (k, d) in rows.into_iter().take(top) {
        println!("  {d:>12.0}  {k}");
    }
}

#[tokio::main]
async fn main() -> Result<()> {
    let args = Args::parse();
    let http = reqwest::Client::builder().pool_max_idle_per_host(256).build()?;
    let stats = Arc::new(Stats::default());
    let api = Api {
        http: http.clone(),
        base: args.base.trim_end_matches('/').to_string(),
        version: args.client_version.clone(),
        stats: stats.clone(),
    };
    let before = scrape(&http, &args.metrics).await;
    let stop = tokio_util::sync::CancellationToken::new();
    let started = Instant::now();
    println!(
        "sw-loadgen: {} worlds × (1 host + {} guests), time-scale {}, {}s after ramp → {}",
        args.worlds, args.guests, args.time_scale, args.duration, args.base
    );
    let mut handles = Vec::new();
    let gap = Duration::from_secs_f64(1.0 / args.ramp_per_sec.max(0.1));
    for i in 0..args.worlds {
        handles.push(tokio::spawn(run_world(api.clone(), args.clone(), i, stop.clone())));
        tokio::time::sleep(gap).await;
    }
    println!("ramp done in {:.1}s; running {}s", started.elapsed().as_secs_f64(), args.duration);
    tokio::time::sleep(Duration::from_secs(args.duration)).await;
    stop.cancel();
    for h in handles {
        match h.await {
            Ok(Err(e)) => stats.error("world", e),
            Err(e) => stats.error("world", e),
            _ => {}
        }
    }
    let wall = started.elapsed();
    stats.report(wall);
    let after = scrape(&http, &args.metrics).await;
    if !after.is_empty() {
        println!("\n== server: DB rows returned per route (delta) ==");
        print_metric_delta(&before, &after, "db_route_rows_returned_total", 12);
        println!("== server: DB rows changed per route (delta) ==");
        print_metric_delta(&before, &after, "db_route_rows_changed_total", 12);
        println!("== server: statements (delta, top) ==");
        print_metric_delta(&before, &after, "db_stmt_total", 12);
        println!("== server: full-scan steps (delta) ==");
        print_metric_delta(&before, &after, "db_fullscan_steps_total", 8);
        for key in [
            "process_resident_memory_bytes",
            "ws_connections",
            "coordinator_worlds_loaded",
            "db_write_queue_depth",
        ] {
            if let Some(v) = after.get(key) {
                println!("{key} = {v}");
            }
        }
    }
    Ok(())
}
