//! End-to-end over real HTTP + WebSocket on an ephemeral port.

use std::sync::Arc;

use futures_util::{SinkExt, StreamExt};
use serde_json::{json, Value};
use sw_core::Config;
use sw_http::bootstrap::{build_inner, open_db, BootOptions};
use sw_http::{build_router, AppState};
use tokio_tungstenite::tungstenite::Message;

struct Server {
    base: String,
    client: reqwest::Client,
}

async fn start() -> Server {
    let mut config = Config::dev();
    config.active_storage_provider = sw_contracts::StorageProviderType::R2;
    config.fs_blob_root = Some(std::env::temp_dir().join(format!("sw-http-smoke-{}", uuid::Uuid::new_v4())));
    config.test_routes = true;
    let config = Arc::new(config);
    let opts = BootOptions {
        config: config.clone(),
        db_path: None,
        db_readers: 0,
        start_realtime_loops: true,
        seed_test_players: true,
    };
    let db = open_db(&opts).unwrap();
    let inner = build_inner(&opts, db, None).await.unwrap();
    let state = AppState::new(inner, config.clone());
    let app = build_router(state);
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
    Server { base: format!("http://{addr}"), client: reqwest::Client::new() }
}

impl Server {
    async fn dev_login(&self, uuid: &str, name: &str) -> String {
        let r = self
            .client
            .post(format!("{}/auth/dev-complete", self.base))
            .json(&json!({"playerUuid": uuid, "playerName": name, "secret": "dev-secret"}))
            .send()
            .await
            .unwrap();
        assert_eq!(r.status(), 200);
        let v: Value = r.json().await.unwrap();
        assert_eq!(v["allowInsecureE4mc"], false);
        v["token"].as_str().unwrap().to_string()
    }
}

#[tokio::test]
async fn http_and_ws_roundtrip() {
    let s = start().await;
    // unknown route → 404 not_found; known path wrong method → 404 too (pattern-before-method)
    let r = s.client.get(format!("{}/nope", s.base)).send().await.unwrap();
    assert_eq!(r.status(), 404);
    let v: Value = r.json().await.unwrap();
    assert_eq!(v["error"], "not_found");
    let r = s.client.get(format!("{}/auth/challenge", s.base)).send().await.unwrap();
    assert_eq!(r.status(), 404);
    // missing auth
    let r = s.client.get(format!("{}/worlds", s.base)).send().await.unwrap();
    assert_eq!(r.status(), 401);
    assert_eq!(r.json::<Value>().await.unwrap()["error"], "missing_auth");
    // invalid json
    let owner = s.dev_login("owner-uuid", "Owner").await;
    let r =
        s.client.post(format!("{}/worlds", s.base)).bearer_auth(&owner).body("{nope").send().await.unwrap();
    assert_eq!(r.status(), 400);
    assert_eq!(r.json::<Value>().await.unwrap()["error"], "invalid_json");
    // challenge → 200 with serverId
    let r = s.client.post(format!("{}/auth/challenge", s.base)).send().await.unwrap();
    assert_eq!(r.status(), 200);
    assert!(r.json::<Value>().await.unwrap()["serverId"].is_string());

    // create world
    let r = s
        .client
        .post(format!("{}/worlds", s.base))
        .bearer_auth(&owner)
        .header("x-sharedworld-version", "0.4.6")
        .json(&json!({"name": "Smoke World", "motdLine1": "hello"}))
        .send()
        .await
        .unwrap();
    assert_eq!(r.status(), 201);
    let created: Value = r.json().await.unwrap();
    let wid = created["world"]["id"].as_str().unwrap().to_string();
    let epoch = created["initialUploadAssignment"]["runtimeEpoch"].as_i64().unwrap();
    let token = created["initialUploadAssignment"]["hostToken"].as_str().unwrap().to_string();
    assert_eq!(created["world"]["storageUsage"], Value::Null);

    // list with etag → 304
    let r = s.client.get(format!("{}/worlds", s.base)).bearer_auth(&owner).send().await.unwrap();
    assert_eq!(r.status(), 200);
    let etag = r.headers().get("etag").unwrap().to_str().unwrap().to_string();
    assert!(etag.starts_with("W/\""));
    let r = s
        .client
        .get(format!("{}/worlds", s.base))
        .bearer_auth(&owner)
        .header("if-none-match", &etag)
        .send()
        .await
        .unwrap();
    assert_eq!(r.status(), 304);

    // guest joins via invite, opens a socket, sees runtime-changed on heartbeat
    let guest = s.dev_login("guest-uuid", "Guest").await;
    let r =
        s.client.post(format!("{}/worlds/{wid}/invites", s.base)).bearer_auth(&owner).send().await.unwrap();
    assert_eq!(r.status(), 201);
    let code = r.json::<Value>().await.unwrap()["code"].as_str().unwrap().to_string();
    let r = s
        .client
        .post(format!("{}/invites/redeem", s.base))
        .bearer_auth(&guest)
        .json(&json!({"code": code}))
        .send()
        .await
        .unwrap();
    assert_eq!(r.status(), 200, "{}", r.text().await.unwrap());

    let ws_url = format!("{}/ws", s.base.replace("http://", "ws://"));
    let req = tokio_tungstenite::tungstenite::client::IntoClientRequest::into_client_request(ws_url.as_str())
        .unwrap();
    let mut req = req;
    req.headers_mut().insert("authorization", format!("Bearer {guest}").parse().unwrap());
    let (mut ws, _) = tokio_tungstenite::connect_async(req).await.unwrap();
    let welcome = ws.next().await.unwrap().unwrap();
    assert_eq!(welcome.into_text().unwrap().as_str(), r#"{"v":1,"type":"welcome"}"#);
    ws.send(Message::Text("sw-keepalive".into())).await.unwrap();
    let ack = ws.next().await.unwrap().unwrap();
    assert_eq!(ack.into_text().unwrap().as_str(), "sw-keepalive-ack");
    ws.send(Message::Text(
        json!({"v":1,"type":"world-presence","worldId":wid,"present":true}).to_string().into(),
    ))
    .await
    .unwrap();

    let r = s
        .client
        .post(format!("{}/worlds/{wid}/heartbeat", s.base))
        .bearer_auth(&owner)
        .json(&json!({"runtimeEpoch": epoch, "hostToken": token, "joinTarget": "join.example:25565"}))
        .send()
        .await
        .unwrap();
    assert_eq!(r.status(), 200);
    let hb: Value = r.json().await.unwrap();
    assert_eq!(hb["phase"], "host-live");
    assert!(hb["memberships"].is_array());
    // the guest's socket receives runtime-changed (possibly after presence-changed)
    let mut got_runtime = false;
    for _ in 0..5 {
        let msg = tokio::time::timeout(std::time::Duration::from_secs(5), ws.next())
            .await
            .unwrap()
            .unwrap()
            .unwrap();
        let v: Value = serde_json::from_str(&msg.into_text().unwrap()).unwrap();
        if v["event"]["kind"] == "runtime-changed" && v["event"]["runtime"]["phase"] == "host-live" {
            got_runtime = true;
            break;
        }
    }
    assert!(got_runtime);
    // unauthenticated ws → 401
    let req = tokio_tungstenite::tungstenite::client::IntoClientRequest::into_client_request(ws_url.as_str())
        .unwrap();
    let err = tokio_tungstenite::connect_async(req).await.unwrap_err();
    assert!(format!("{err}").contains("401"), "{err}");
    // test routes present
    let r = s.client.get(format!("{}/__test/health", s.base)).send().await.unwrap();
    assert_eq!(r.status(), 200);
}
