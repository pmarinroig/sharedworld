//! Ports of `backend/test/parity/*`: the mod ↔ backend contracts that nothing
//! at build time links — route table, error codes/reasons, timing constants —
//! plus config parity with the worker's `Env` (every `wrangler.toml` knob has
//! a `Config` field), all checked against the real Java/TS sources.

use std::collections::{BTreeMap, BTreeSet};
use std::path::{Path, PathBuf};
use std::sync::Arc;

use regex::Regex;
use sw_core::Config;
use sw_http::bootstrap::{build_inner, open_db, BootOptions};
use sw_http::{build_router, AppState};

fn repo_root() -> PathBuf {
    // crates/sw-http → public/packages
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../../..").canonicalize().unwrap()
}
fn fabric_main_java() -> PathBuf {
    repo_root().join("fabric/src/main/java/link/sharedworld")
}
fn read_java(rel: &str) -> String {
    std::fs::read_to_string(fabric_main_java().join(rel)).unwrap_or_else(|e| panic!("{rel}: {e}"))
}
fn walk(dir: &Path, exts: &[&str]) -> Vec<PathBuf> {
    walkdir::WalkDir::new(dir)
        .into_iter()
        .filter_map(Result::ok)
        .filter(|e| e.file_type().is_file())
        .map(|e| e.into_path())
        .filter(|p| {
            p.extension().and_then(|x| x.to_str()).map(|x| exts.contains(&x)).unwrap_or(false)
                && !p.components().any(|c| c.as_os_str() == "target")
        })
        .collect()
}

// ------------------------------------------------------------------ routes

fn java_path_expression_to_template(expression: &str) -> String {
    let mut idx = 0;
    expression
        .split('+')
        .map(str::trim)
        .map(|part| {
            if let Some(lit) = part.strip_prefix('"') {
                lit.trim_end_matches('"').to_string()
            } else {
                idx += 1;
                format!(":param{idx}")
            }
        })
        .collect()
}

fn concretize(template: &str) -> String {
    Regex::new(r":[A-Za-z0-9]+").unwrap().replace_all(template, "pv").into_owned()
}

fn extract_mod_routes(source: &str) -> Vec<(String, String)> {
    let mut routes = Vec::new();
    let request_call = Regex::new(r#"request\(\s*"(GET|POST|PATCH|DELETE|PUT)",\s*([^,]+),"#).unwrap();
    for m in request_call.captures_iter(source) {
        if !m[2].contains('"') {
            continue;
        }
        routes.push((m[1].to_string(), java_path_expression_to_template(&m[2])));
    }
    let conditional = Regex::new(r"conditionalGet\(\s*([^,]+),").unwrap();
    for m in conditional.captures_iter(source) {
        if !m[1].contains('"') {
            continue;
        }
        routes.push(("GET".into(), java_path_expression_to_template(&m[1])));
    }
    routes
}

async fn test_app() -> axum::Router {
    let mut config = Config::dev();
    config.active_storage_provider = sw_contracts::StorageProviderType::R2;
    config.fs_blob_root = Some(std::env::temp_dir().join(format!("sw-parity-{}", std::process::id())));
    let config = Arc::new(config);
    let opts = BootOptions {
        config: config.clone(),
        db_path: None,
        db_readers: 0,
        start_realtime_loops: false,
        seed_test_players: false,
    };
    let db = open_db(&opts).unwrap();
    let inner = build_inner(&opts, db, None).await.unwrap();
    build_router(AppState::new(inner, config))
}

/// A route "exists" when the router does not answer with its own
/// `404 not_found` fallback (auth extractors and handlers answer anything
/// else — 401, 400, 404 world_not_found — before the fallback would).
async fn backend_serves(app: &axum::Router, method: &str, template: &str) -> bool {
    use tower::ServiceExt;
    let req = axum::http::Request::builder()
        .method(method)
        .uri(concretize(template))
        .body(axum::body::Body::empty())
        .unwrap();
    let res = app.clone().oneshot(req).await.unwrap();
    if res.status() != axum::http::StatusCode::NOT_FOUND {
        return true;
    }
    let body = axum::body::to_bytes(res.into_body(), 1 << 20).await.unwrap();
    let v: serde_json::Value = serde_json::from_slice(&body).unwrap_or_default();
    !(v["error"] == "not_found" && v["message"] == "Route not found.")
}

#[tokio::test]
async fn every_route_the_mod_calls_is_served() {
    let source = read_java("api/SharedWorldApiClient.java");
    assert!(source.contains(r#""/worlds/" + worldId + "/downloads/plan""#));
    let mut routes = extract_mod_routes(&source);
    routes.push(("GET".into(), "/worlds/:worldId/downloads/plan".into()));
    assert!(routes.len() >= 25, "extraction regex rotted: {} routes", routes.len());
    let app = test_app().await;
    let mut missing = Vec::new();
    for (method, template) in &routes {
        if !backend_serves(&app, method, template).await {
            missing.push(format!("{method} {template}"));
        }
    }
    assert!(missing.is_empty(), "routes the mod calls but the server does not serve: {missing:?}");
}

#[tokio::test]
async fn wrong_method_on_known_path_is_route_not_found() {
    // Pattern-before-method dispatch (worker parity): the mod treats 404 on
    // a known path the same as an unknown route.
    let app = test_app().await;
    assert!(!backend_serves(&app, "DELETE", "/auth/challenge").await);
}

// --------------------------------------------------------------- error codes

fn server_emitted_codes() -> BTreeSet<String> {
    let re = Regex::new(r#"HttpError::new\(\s*\d+,\s*"([a-z_]+)""#).unwrap();
    let mut codes = BTreeSet::new();
    for file in walk(&repo_root().join("server/crates"), &["rs"]) {
        let src = std::fs::read_to_string(&file).unwrap();
        for m in re.captures_iter(&src) {
            codes.insert(m[1].to_string());
        }
    }
    // Constructors that bake the code in.
    codes.insert("internal_error".into());
    codes
}

fn server_emitted_reasons() -> BTreeSet<String> {
    let re = Regex::new(r#"(?:with_reason\(|reason:?[^;\n]*?|=> )"([a-z]+(?:_[a-z]+)+)""#).unwrap();
    let mut reasons = BTreeSet::new();
    for file in walk(&repo_root().join("server/crates"), &["rs"]) {
        let src = std::fs::read_to_string(&file).unwrap();
        for m in re.captures_iter(&src) {
            reasons.insert(m[1].to_string());
        }
    }
    reasons
}

fn mod_classified_literals() -> (BTreeMap<String, Vec<String>>, BTreeMap<String, Vec<String>>) {
    let re = Regex::new(r#""([a-z]+(?:_[a-z]+)+)"\.equals\(([^)]*)"#).unwrap();
    let ctx = Regex::new(r"errorCode\(|\.error\(\)|\.reason\(\)").unwrap();
    let mut codes: BTreeMap<String, Vec<String>> = BTreeMap::new();
    let mut reasons: BTreeMap<String, Vec<String>> = BTreeMap::new();
    let base = fabric_main_java();
    for file in walk(&base, &["java"]) {
        let lines: Vec<String> = std::fs::read_to_string(&file).unwrap().lines().map(String::from).collect();
        for (index, line) in lines.iter().enumerate() {
            for m in re.captures_iter(line) {
                let window = lines[index.saturating_sub(12)..=index].join("\n");
                if ctx.is_match(&window) {
                    let target = if m[2].contains(".reason(") { &mut reasons } else { &mut codes };
                    target.entry(m[1].to_string()).or_default().push(format!(
                        "{}:{}",
                        file.strip_prefix(&base).unwrap().display(),
                        index + 1
                    ));
                }
            }
        }
    }
    (codes, reasons)
}

#[test]
fn error_codes_and_reasons_the_mod_classifies_are_emitted() {
    let emitted = server_emitted_codes();
    let emitted_reasons = server_emitted_reasons();
    let (codes, reasons) = mod_classified_literals();
    assert!(emitted.len() >= 20, "{emitted:?}");
    assert!(codes.len() >= 7, "{codes:?}");
    assert!(codes.contains_key("world_not_found") && codes.contains_key("invite_expired"));
    assert!(emitted_reasons.contains("lease_expired"), "{emitted_reasons:?}");
    assert!(reasons.contains_key("lease_expired"));
    let client_synthesized = ["http_error"];
    let unknown: Vec<String> = codes
        .iter()
        .filter(|(c, _)| !emitted.contains(*c) && !client_synthesized.contains(&c.as_str()))
        .map(|(c, u)| format!("{c} (classified at {})", u.join(", ")))
        .collect();
    assert!(unknown.is_empty(), "mod classifies codes the server never emits: {unknown:?}");
    let unknown: Vec<String> = reasons
        .iter()
        .filter(|(r, _)| !emitted_reasons.contains(*r))
        .map(|(r, u)| format!("{r} (classified at {})", u.join(", ")))
        .collect();
    assert!(unknown.is_empty(), "mod classifies reasons the server never emits: {unknown:?}");
}

// ------------------------------------------------------------- timing consts

fn extract_java_ms_constants(source: &str) -> BTreeMap<String, i64> {
    let re = Regex::new(r"static final long ([A-Z_]+_MS) = ([0-9_L*\s]+);").unwrap();
    re.captures_iter(source)
        .map(|m| {
            let value =
                m[2].replace(['_', 'L', ' '], "").split('*').map(|f| f.parse::<i64>().unwrap()).product();
            (m[1].to_string(), value)
        })
        .collect()
}

fn contract_ms_constants() -> BTreeMap<String, i64> {
    // Parsed from the source so a new constant cannot hide from the
    // "unmapped" check (Rust has no reflection over module items).
    let src = std::fs::read_to_string(repo_root().join("server/crates/sw-contracts/src/timing.rs")).unwrap();
    let re = Regex::new(r"pub const ([A-Z_]+_MS): i64 = ([0-9_*\s]+);").unwrap();
    let parsed: BTreeMap<String, i64> = re
        .captures_iter(&src)
        .map(|m| {
            let value = m[2].replace(['_', ' '], "").split('*').map(|f| f.parse::<i64>().unwrap()).product();
            (m[1].to_string(), value)
        })
        .collect();
    // The parse must agree with the compiled values for the mapped names.
    assert_eq!(parsed["HOST_HEARTBEAT_INTERVAL_MS"], sw_contracts::HOST_HEARTBEAT_INTERVAL_MS);
    assert_eq!(parsed["AUTOSAVE_INTERVAL_MS"], sw_contracts::AUTOSAVE_INTERVAL_MS);
    assert_eq!(parsed["HOST_LEASE_TIMEOUT_MS"], sw_contracts::HOST_LEASE_TIMEOUT_MS);
    assert_eq!(
        parsed["PLAYER_PRESENCE_HEARTBEAT_INTERVAL_MS"],
        sw_contracts::PLAYER_PRESENCE_HEARTBEAT_INTERVAL_MS
    );
    parsed
}

#[test]
fn timing_constants_agree_with_the_mod() {
    let mapped = [
        ("HOST_HEARTBEAT_INTERVAL_MS", "host/SharedWorldHostingManager.java", "HEARTBEAT_INTERVAL_MS"),
        ("AUTOSAVE_INTERVAL_MS", "host/SharedWorldHostingManager.java", "DEFAULT_AUTOSAVE_INTERVAL_MS"),
        ("HOST_LEASE_TIMEOUT_MS", "host/SharedWorldHostingManager.java", "HOST_CONFIRM_TIMEOUT_MS"),
        ("PLAYER_PRESENCE_HEARTBEAT_INTERVAL_MS", "SharedWorldPresenceManager.java", "HEARTBEAT_INTERVAL_MS"),
    ];
    let java_only: BTreeSet<&str> = [
        "host/SharedWorldHostingManager.java#HEARTBEAT_RETRY_INTERVAL_MS",
        "host/SharedWorldHostingManager.java#JOIN_TARGET_TIMEOUT_MS",
        "host/SharedWorldHostingManager.java#MAX_SUGGESTED_HEARTBEAT_INTERVAL_MS",
        "host/SharedWorldHostingManager.java#MAX_SUGGESTED_AUTOSAVE_INTERVAL_MS",
        "host/SharedWorldHostingManager.java#AUTOSAVE_REANNOUNCE_INTERVAL_MS",
        "SharedWorldPresenceManager.java#MAX_SUGGESTED_HEARTBEAT_INTERVAL_MS",
        "host/SharedWorldHostingManager.java#PUSH_CONNECTED_HEARTBEAT_INTERVAL_MS",
        "host/SharedWorldHostingManager.java#GAMERULES_LOCAL_POLL_INTERVAL_MS",
        "SharedWorldPresenceManager.java#PUSH_CONNECTED_HEARTBEAT_INTERVAL_MS",
    ]
    .into_iter()
    .collect();
    let contract_only: BTreeSet<&str> = [
        "HOST_LIVE_LEASE_TIMEOUT_MS",
        "HANDOFF_WAITER_TIMEOUT_MS",
        "PLAYER_PRESENCE_TIMEOUT_MS",
        "INVITE_TTL_MS",
        "STORAGE_LINK_TTL_MS",
    ]
    .into_iter()
    .collect();
    let contracts = contract_ms_constants();
    assert!(contracts.len() >= 9, "{contracts:?}");
    for (rs_name, java_file, java_const) in mapped {
        let java = extract_java_ms_constants(&read_java(java_file));
        let java_value = java.get(java_const).unwrap_or_else(|| panic!("{java_file}#{java_const} missing"));
        let rs_value = contracts.get(rs_name).unwrap_or_else(|| panic!("{rs_name} missing in timing.rs"));
        assert_eq!(java_value, rs_value, "{rs_name} != {java_file}#{java_const}");
    }
    let mut unmapped = Vec::new();
    for java_file in ["host/SharedWorldHostingManager.java", "SharedWorldPresenceManager.java"] {
        for name in extract_java_ms_constants(&read_java(java_file)).keys() {
            let qualified = format!("{java_file}#{name}");
            let is_mapped = mapped.iter().any(|(_, f, c)| *f == java_file && c == name);
            if !is_mapped && !java_only.contains(qualified.as_str()) {
                unmapped.push(qualified);
            }
        }
    }
    assert!(unmapped.is_empty(), "new Java timing constants must be mapped or allowlisted: {unmapped:?}");
    let unmapped: Vec<&String> = contracts
        .keys()
        .filter(|n| !mapped.iter().any(|(r, _, _)| r == n) && !contract_only.contains(n.as_str()))
        .collect();
    assert!(unmapped.is_empty(), "new contract timing constants must be mapped or allowlisted: {unmapped:?}");
}

// ------------------------------------------------------------- config parity

#[test]
fn every_worker_env_key_has_a_config_field() {
    let env_ts = std::fs::read_to_string(repo_root().join("backend/src/env.ts")).unwrap();
    let re = Regex::new(r"(?m)^\s+([A-Z_]+)\??:").unwrap();
    let bindings = ["DB", "BLOBS", "WORLD_COORDINATOR", "USER_GATEWAY"];
    let config_rs = std::fs::read_to_string(repo_root().join("server/crates/sw-core/src/config.rs")).unwrap();
    let fields: BTreeSet<String> = Regex::new(r"pub ([a-z_0-9]+):")
        .unwrap()
        .captures_iter(&config_rs)
        .map(|m| m[1].to_string())
        .collect();
    let missing: Vec<String> = re
        .captures_iter(&env_ts)
        .map(|m| m[1].to_string())
        .filter(|k| !bindings.contains(&k.as_str()))
        .filter(|k| !fields.contains(&k.to_lowercase()))
        .collect();
    assert!(missing.is_empty(), "worker Env keys without a Config field: {missing:?}");
}
