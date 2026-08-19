//! `/storage/*` (`router/storage-routes.ts`) incl. the Google callback HTML page.

use std::collections::HashMap;
use std::sync::Arc;

use axum::extract::{Path, Query, State};
use axum::http::{header, HeaderValue, StatusCode};
use axum::response::Response;
use axum::routing::{get, post};
use axum::Router;
use sw_contracts::*;
use sw_core::time;

use super::param;
use crate::auth::Auth;
use crate::body::JsonBody;
use crate::error::{json_response, ok_json, ApiResult};
use crate::state::AppState;

pub fn routes() -> Router<Arc<AppState>> {
    Router::new()
        .route("/storage/account", get(account))
        .route("/storage/link-sessions", post(create_link))
        .route("/storage/link-sessions/{sessionId}/cancel", post(cancel_link))
        .route("/storage/link-sessions/{sessionId}", get(get_link))
        .route("/storage/google/callback", get(google_callback))
}

async fn account(State(state): State<Arc<AppState>>, Auth(ctx): Auth) -> ApiResult<Response> {
    Ok(ok_json(&state.svc().storage_links.get_storage_account_summary(&ctx).await?))
}

async fn create_link(
    State(state): State<Arc<AppState>>,
    Auth(ctx): Auth,
    JsonBody(req): JsonBody<CreateStorageLinkRequest>,
) -> ApiResult<Response> {
    Ok(json_response(
        StatusCode::CREATED,
        &state.svc().storage_links.create_storage_link(&ctx, &req, time::now()).await?,
    ))
}

async fn cancel_link(
    State(state): State<Arc<AppState>>,
    Auth(ctx): Auth,
    p: Path<HashMap<String, String>>,
) -> ApiResult<Response> {
    let id = param(&p, "sessionId")?;
    Ok(ok_json(&state.svc().storage_links.cancel_storage_link(&ctx, &id, time::now()).await?))
}

async fn get_link(
    State(state): State<Arc<AppState>>,
    Auth(ctx): Auth,
    p: Path<HashMap<String, String>>,
) -> ApiResult<Response> {
    let id = param(&p, "sessionId")?;
    Ok(ok_json(&state.svc().storage_links.get_storage_link_session(&ctx, &id, time::now()).await?))
}

async fn google_callback(
    State(state): State<Arc<AppState>>,
    Query(q): Query<HashMap<String, String>>,
) -> Response {
    let state_param = q.get("state").cloned();
    let session_id = q
        .get("sessionId")
        .cloned()
        .or_else(|| state_param.as_ref().map(|s| s.split(':').next().unwrap_or("").to_string()))
        .unwrap_or_default();
    let request = StorageLinkCompleteRequest {
        session_id: session_id.clone(),
        code: q.get("code").cloned(),
        state: state_param,
        mock_email: q.get("mockEmail").cloned(),
    };
    match state.svc().storage_links.complete_storage_link(&session_id, &request, time::now()).await {
        Ok(session) => render_storage_link_page(
            200,
            "success",
            "Google Drive linked",
            "Return to Minecraft.",
            session.linked_account_email.as_deref().or(Some("linked account")),
        ),
        Err(e) => {
            let cancelled = e.code == "storage_link_cancelled";
            render_storage_link_page(
                e.status,
                "error",
                if cancelled { "Link no longer active" } else { "Link failed" },
                if cancelled {
                    "This Google Drive link was replaced or cancelled. Return to Minecraft and start again."
                } else {
                    &e.message
                },
                None,
            )
        }
    }
}

fn escape_html(v: &str) -> String {
    v.replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
        .replace('\'', "&#39;")
}

/// The browser-facing result page for the Google Drive link callback — the
/// only HTML the backend serves (`router/link-result-page.ts`).
pub fn render_storage_link_page(
    status: u16,
    tone: &str,
    title: &str,
    message: &str,
    linked_account_email: Option<&str>,
) -> Response {
    let accent_soft = if tone == "success" { "rgba(92, 127, 104, 0.12)" } else { "rgba(155, 95, 95, 0.12)" };
    let account_markup = match linked_account_email {
        Some(email) => format!(
            r#"
      <div class="account">
        <p class="account-value">{}</p>
      </div>
    "#,
            escape_html(email)
        ),
        None => String::new(),
    };
    let html = format!(
        r#"<!doctype html>
<html lang="en">
  <head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>SharedWorld</title>
    <style>
      :root {{
        color-scheme: light;
      }}

      * {{
        box-sizing: border-box;
      }}

      body {{
        margin: 0;
        min-height: 100vh;
        font-family: "Avenir Next", "Segoe UI", sans-serif;
        background:
          radial-gradient(circle at top, rgba(123, 161, 144, 0.16), transparent 36%),
          linear-gradient(180deg, #eef3f1 0%, #f6f1e9 100%);
        color: #1f2933;
      }}

      main {{
        min-height: 100vh;
        display: grid;
        place-items: center;
        padding: 24px;
      }}

      .shell {{
        width: min(100%, 520px);
      }}

      .brand {{
        margin: 0 0 16px;
        text-align: center;
        font-size: 0.72rem;
        font-weight: 600;
        letter-spacing: 0.18em;
        text-transform: uppercase;
        color: #6d7c76;
      }}

      .card {{
        border-radius: 24px;
        border: 1px solid rgba(95, 111, 104, 0.14);
        background: rgba(255, 255, 255, 0.82);
        box-shadow: 0 24px 60px rgba(31, 41, 51, 0.1);
        padding: 36px 32px 32px;
        backdrop-filter: blur(8px);
      }}

      h1 {{
        margin: 0;
        font-size: clamp(2rem, 4vw, 2.4rem);
        line-height: 1.08;
        color: #14212b;
      }}

      .message {{
        margin: 14px 0 0;
        font-size: 1rem;
        line-height: 1.55;
        color: #556471;
      }}

      .account {{
        margin-top: 22px;
        padding-top: 18px;
        border-top: 1px solid {accent_soft};
      }}

      .account-value {{
        margin: 0;
        font-size: 1.05rem;
        font-weight: 600;
        line-height: 1.5;
        color: #14212b;
        overflow-wrap: anywhere;
      }}

      @media (max-width: 640px) {{
        .card {{
          padding: 28px 24px 24px;
          border-radius: 20px;
        }}
      }}
    </style>
  </head>
  <body>
    <main>
      <div class="shell">
        <p class="brand">SharedWorld</p>
        <section class="card">
          <h1>{title}</h1>
          {account_markup}
          <p class="message">{message}</p>
        </section>
      </div>
    </main>
  </body>
</html>"#,
        title = escape_html(title),
        message = escape_html(message),
    );
    let mut resp = Response::new(axum::body::Body::from(html));
    *resp.status_mut() = StatusCode::from_u16(status).unwrap_or(StatusCode::INTERNAL_SERVER_ERROR);
    resp.headers_mut().insert(header::CONTENT_TYPE, HeaderValue::from_static("text/html; charset=utf-8"));
    resp
}
