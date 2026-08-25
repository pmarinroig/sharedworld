//! Edge + core restart drill over real processes and Unix sockets:
//! a client WebSocket through the edge survives a core restart, the edge
//! answers keepalives meanwhile, HTTP is queued (not 503'd) across the
//! restart, and the re-attached socket keeps receiving events.

use std::path::PathBuf;
use std::process::{Child, Command};
use std::time::{Duration, Instant};

use futures_util::{SinkExt, StreamExt};
use serde_json::{json, Value};
use tokio_tungstenite::tungstenite::Message;

fn bin(name: &str) -> PathBuf {
    let edge = PathBuf::from(env!("CARGO_BIN_EXE_swedge"));
    let dir = edge.parent().unwrap().to_path_buf();
    let candidate = dir.join(name);
    assert!(candidate.exists(), "{} not built (cargo build -p {name})", candidate.display());
    candidate
}

fn free_port() -> u16 {
    std::net::TcpListener::bind("127.0.0.1:0").unwrap().local_addr().unwrap().port()
}

struct Core {
    child: Child,
}

/// A failed assertion must not leak the spawned binaries (they otherwise
/// outlive the test process and pile up across runs).
impl Drop for Core {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

fn log_file(dir: &std::path::Path, name: &str) -> std::fs::File {
    std::fs::OpenOptions::new().create(true).append(true).open(dir.join(name)).unwrap()
}

fn spawn_core(dir: &std::path::Path, db: &std::path::Path, tcp_port: u16) -> Core {
    let child = Command::new(bin("swcore"))
        .arg("--dev")
        .arg("--db")
        .arg(db)
        .arg("--listen")
        .arg(format!("127.0.0.1:{tcp_port}"))
        .arg("--metrics-listen")
        .arg(format!("127.0.0.1:{}", free_port()))
        .arg("--uds-http")
        .arg(dir.join("core-http.sock"))
        .arg("--uds-ws")
        .arg(dir.join("core-ws.sock"))
        .env("SW_ACTIVE_STORAGE_PROVIDER", "r2")
        .env("SW_FS_BLOB_ROOT", dir.join("blobs"))
        .env("RUST_LOG", "debug,hyper=info,h2=info,tower=info")
        .stdout(log_file(dir, "core.log"))
        .stderr(log_file(dir, "core.log"))
        .spawn()
        .expect("spawn swcore");
    Core { child }
}

/// Reads frames until the keepalive ack arrives. Event frames can
/// legitimately interleave ahead of the ack (the presence-changed broadcast
/// triggered by this socket's own world-presence subscription races the
/// edge's ack), so the drill skips them instead of failing on ordering.
async fn expect_keepalive_ack(
    ws: &mut tokio_tungstenite::WebSocketStream<tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>>,
) {
    for _ in 0..5 {
        let msg = tokio::time::timeout(Duration::from_secs(3), ws.next()).await.unwrap().unwrap().unwrap();
        let text = msg.into_text().unwrap();
        if text.as_str() == "sw-keepalive-ack" {
            return;
        }
        assert!(
            text.contains(r#""type":"event""#),
            "unexpected frame while waiting for keepalive ack: {text}"
        );
    }
    panic!("keepalive ack never arrived");
}

async fn wait_http_ok(url: &str, timeout: Duration) {
    let client = reqwest::Client::new();
    let deadline = Instant::now() + timeout;
    loop {
        if let Ok(r) = client.get(url).send().await {
            if r.status().is_success() || r.status().as_u16() == 404 {
                return;
            }
        }
        assert!(Instant::now() < deadline, "timeout waiting for {url}");
        tokio::time::sleep(Duration::from_millis(100)).await;
    }
}

#[tokio::test(flavor = "multi_thread")]
async fn socket_survives_core_restart() {
    let dir = tempfile::tempdir().unwrap();
    let db = dir.path().join("sw.db");
    let core_port = free_port();
    let edge_port = free_port();
    let mut core = spawn_core(dir.path(), &db, core_port);
    let edge = Command::new(bin("swedge"))
        .arg("--plain-listen")
        .arg(format!("127.0.0.1:{edge_port}"))
        .arg("--metrics-listen")
        .arg(format!("127.0.0.1:{}", free_port()))
        .arg("--core-http-socket")
        .arg(dir.path().join("core-http.sock"))
        .arg("--core-ws-socket")
        .arg(dir.path().join("core-ws.sock"))
        .env("RUST_LOG", "debug,hyper=info,h2=info,tower=info")
        .stdout(log_file(dir.path(), "edge.log"))
        .stderr(log_file(dir.path(), "edge.log"))
        .spawn()
        .expect("spawn swedge");
    let mut edge = Core { child: edge };
    let base = format!("http://127.0.0.1:{edge_port}");
    wait_http_ok(&format!("{base}/auth/challenge"), Duration::from_secs(20)).await;
    let client = reqwest::Client::new();

    // dev login through the edge (proxied HTTP)
    let login = |uuid: &'static str, name: &'static str| {
        let client = client.clone();
        let base = base.clone();
        async move {
            let r = client
                .post(format!("{base}/auth/dev-complete"))
                .json(&json!({"playerUuid": uuid, "playerName": name, "secret": "dev-secret"}))
                .send()
                .await
                .unwrap();
            assert_eq!(r.status(), 200);
            r.json::<Value>().await.unwrap()["token"].as_str().unwrap().to_string()
        }
    };
    let owner = login("owner-uuid", "Owner").await;
    let guest = login("guest-uuid", "Guest").await;

    // world + invite + redeem
    let created: Value = client
        .post(format!("{base}/worlds"))
        .bearer_auth(&owner)
        .json(&json!({"name": "Drill World"}))
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    let wid = created["world"]["id"].as_str().unwrap().to_string();
    let epoch = created["initialUploadAssignment"]["runtimeEpoch"].as_i64().unwrap();
    let token = created["initialUploadAssignment"]["hostToken"].as_str().unwrap().to_string();
    let invite: Value = client
        .post(format!("{base}/worlds/{wid}/invites"))
        .bearer_auth(&owner)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    let r = client
        .post(format!("{base}/invites/redeem"))
        .bearer_auth(&guest)
        .json(&json!({"code": invite["code"]}))
        .send()
        .await
        .unwrap();
    assert_eq!(r.status(), 200);

    // guest socket through the edge
    let ws_url = format!("ws://127.0.0.1:{edge_port}/ws");
    let mut req =
        tokio_tungstenite::tungstenite::client::IntoClientRequest::into_client_request(ws_url.as_str())
            .unwrap();
    req.headers_mut().insert("authorization", format!("Bearer {guest}").parse().unwrap());
    let (mut ws, _) = tokio_tungstenite::connect_async(req).await.unwrap();
    let welcome = ws.next().await.unwrap().unwrap().into_text().unwrap();
    assert_eq!(welcome.as_str(), r#"{"v":1,"type":"welcome"}"#);
    ws.send(Message::Text(
        json!({"v":1,"type":"world-presence","worldId":wid,"present":true}).to_string().into(),
    ))
    .await
    .unwrap();
    ws.send(Message::Text("sw-keepalive".into())).await.unwrap();
    expect_keepalive_ack(&mut ws).await;

    // kill the core, keepalive still answered by the edge, socket still open
    core.child.kill().unwrap();
    core.child.wait().unwrap();
    tokio::time::sleep(Duration::from_millis(300)).await;
    ws.send(Message::Text("sw-keepalive".into())).await.unwrap();
    expect_keepalive_ack(&mut ws).await;

    // HTTP during the outage is queued: fire a request, then restart the core
    let c2 = client.clone();
    let b2 = base.clone();
    let o2 = owner.clone();
    let queued = tokio::spawn(async move {
        c2.get(format!("{b2}/worlds")).bearer_auth(&o2).send().await.unwrap().status().as_u16()
    });
    tokio::time::sleep(Duration::from_millis(500)).await;
    let mut core = spawn_core(dir.path(), &db, core_port);
    assert_eq!(queued.await.unwrap(), 200, "request queued across the restart must succeed");

    // the re-attached socket still receives events: host heartbeat → runtime-changed
    let r = client
        .post(format!("{base}/worlds/{wid}/heartbeat"))
        .bearer_auth(&owner)
        .json(&json!({"runtimeEpoch": epoch, "hostToken": token, "joinTarget": "join.example:25565"}))
        .send()
        .await
        .unwrap();
    assert_eq!(r.status(), 200, "{}", r.text().await.unwrap());
    let mut got = false;
    for _ in 0..6 {
        let Ok(msg) = tokio::time::timeout(Duration::from_secs(5), ws.next()).await else {
            eprintln!(
                "--- core.log ---\n{}",
                std::fs::read_to_string(dir.path().join("core.log")).unwrap_or_default()
            );
            eprintln!(
                "--- edge.log ---\n{}",
                std::fs::read_to_string(dir.path().join("edge.log")).unwrap_or_default()
            );
            panic!("event after restart timed out");
        };
        let msg = msg.unwrap().unwrap();
        if let Ok(text) = msg.into_text() {
            if text.contains("runtime-changed") && text.contains("host-live") {
                got = true;
                break;
            }
        }
    }
    assert!(got, "re-attached socket receives runtime-changed");
    // presence replayed: the world shows the guest online
    let world: Value = client
        .get(format!("{base}/worlds/{wid}"))
        .bearer_auth(&owner)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert!(world["onlinePlayerNames"].as_array().unwrap().iter().any(|n| n == "Guest"), "{world}");

    let _ = ws.close(None).await;
    core.child.kill().unwrap();
    let _ = core.child.wait();
    edge.child.kill().unwrap();
    let _ = edge.child.wait();
}
