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
        .route("/storage/account", get(account).delete(unlink_account))
        .route("/storage/link-sessions", post(create_link))
        .route("/storage/link-sessions/{sessionId}/cancel", post(cancel_link))
        .route("/storage/link-sessions/{sessionId}", get(get_link))
        .route("/storage/google/callback", get(google_callback))
        .route("/storage/s3/link", get(s3_link_form).post(s3_link_submit))
}

fn provider_param(q: &HashMap<String, String>) -> Option<StorageProviderType> {
    q.get("provider").and_then(|v| StorageProviderType::parse(v))
}

async fn account(
    State(state): State<Arc<AppState>>,
    Auth(ctx): Auth,
    Query(q): Query<HashMap<String, String>>,
) -> ApiResult<Response> {
    Ok(ok_json(&state.svc().storage_links.get_storage_account_summary(&ctx, provider_param(&q)).await?))
}

async fn unlink_account(
    State(state): State<Arc<AppState>>,
    Auth(ctx): Auth,
    Query(q): Query<HashMap<String, String>>,
) -> ApiResult<Response> {
    sw_core::service::account::unlink_storage_account(&state.svc(), &ctx, provider_param(&q), time::now())
        .await?;
    Ok(crate::error::no_content())
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

/// The S3 bring-your-own-bucket form. Unauthenticated but bound to a pending
/// link session + state nonce, exactly like the Google OAuth callback.
async fn s3_link_form(
    State(state): State<Arc<AppState>>,
    Query(q): Query<HashMap<String, String>>,
) -> Response {
    if !state.svc().config.s3_link_enabled {
        return render_storage_link_page(
            404,
            "error",
            "Not available",
            "Linking S3 buckets is disabled on this server.",
            None,
        );
    }
    let session = q.get("session").cloned().unwrap_or_default();
    let link_state = q.get("state").cloned().unwrap_or_default();
    render_s3_link_form(&session, &link_state, &sw_core::storage::link_service::S3LinkForm::default(), None)
}

async fn s3_link_submit(
    State(state): State<Arc<AppState>>,
    axum::extract::Form(form): axum::extract::Form<HashMap<String, String>>,
) -> Response {
    if !state.svc().config.s3_link_enabled {
        return render_storage_link_page(
            404,
            "error",
            "Not available",
            "Linking S3 buckets is disabled on this server.",
            None,
        );
    }
    let session = form.get("session").cloned().unwrap_or_default();
    let link_state = form.get("state").cloned().unwrap_or_default();
    let fields = sw_core::storage::link_service::S3LinkForm {
        endpoint: form.get("endpoint").cloned().unwrap_or_default(),
        region: form.get("region").cloned().unwrap_or_default(),
        bucket: form.get("bucket").cloned().unwrap_or_default(),
        access_key_id: form.get("access_key_id").cloned().unwrap_or_default(),
        secret_access_key: form.get("secret_access_key").cloned().unwrap_or_default(),
        key_prefix: form.get("key_prefix").cloned().unwrap_or_default(),
    };
    match state
        .svc()
        .storage_links
        .complete_s3_link(&session, Some(link_state.as_str()).filter(|s| !s.is_empty()), &fields, time::now())
        .await
    {
        Ok(linked) => render_storage_link_page(
            200,
            "success",
            "Bucket linked",
            "Return to Minecraft.",
            linked.linked_account_email.as_deref().or(Some("linked bucket")),
        ),
        // Form-shaped problems (typo, failed probe) re-render the form with
        // the message so the session survives a fixable mistake.
        Err(e) if e.code == "s3_link_form_invalid" || e.code == "storage_account_already_linked" => {
            render_s3_link_form(&session, &link_state, &fields, Some(&e.message))
        }
        Err(e) => {
            let cancelled = e.code == "storage_link_cancelled";
            render_storage_link_page(
                e.status,
                "error",
                if cancelled { "Link no longer active" } else { "Link failed" },
                if cancelled {
                    "This bucket link was replaced or cancelled. Return to Minecraft and start again."
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

/// The S3 link form page, in the same visual shell as the link result page.
/// The secret field is never echoed back on a re-render.
fn render_s3_link_form(
    session: &str,
    link_state: &str,
    fields: &sw_core::storage::link_service::S3LinkForm,
    error: Option<&str>,
) -> Response {
    let error_markup = match error {
        Some(message) => format!(
            r#"<p class="message" style="color:#9b3f3f; background: rgba(155, 95, 95, 0.10); border-radius: 10px; padding: 10px 12px;">{}</p>"#,
            escape_html(message)
        ),
        None => String::new(),
    };
    let field = |name: &str, label: &str, value: &str, placeholder: &str, kind: &str, hint: &str| {
        format!(
            r#"
      <label class="field">
        <span class="field-label">{label}</span>
        <input type="{kind}" name="{name}" value="{value}" placeholder="{placeholder}" autocomplete="off" spellcheck="false">
        {hint_markup}
      </label>"#,
            label = escape_html(label),
            name = name,
            kind = kind,
            value = escape_html(value),
            placeholder = escape_html(placeholder),
            hint_markup = if hint.is_empty() {
                String::new()
            } else {
                format!(r#"<span class="field-hint">{}</span>"#, escape_html(hint))
            },
        )
    };
    let body = format!(
        r#"<h1>Connect an S3 bucket</h1>
    <p class="message">SharedWorld will store this world's data in your own S3-compatible bucket (Cloudflare R2, Backblaze B2, MinIO...). The bucket must be reachable from the internet. Credentials are stored encrypted and only used by the SharedWorld server.</p>
    {error_markup}
    <form method="post" action="/storage/s3/link">
      <input type="hidden" name="session" value="{session}">
      <input type="hidden" name="state" value="{link_state}">
      {endpoint}
      {region}
      {bucket}
      {access_key}
      {secret}
      {prefix}
      <button type="submit">Test &amp; link bucket</button>
    </form>"#,
        session = escape_html(session),
        link_state = escape_html(link_state),
        endpoint = field(
            "endpoint",
            "Endpoint URL",
            &fields.endpoint,
            "https://<accountid>.r2.cloudflarestorage.com",
            "text",
            "The S3 API origin, without the bucket name.",
        ),
        region =
            field("region", "Region", &fields.region, "auto", "text", "Leave empty for auto (R2/MinIO)."),
        bucket = field("bucket", "Bucket name", &fields.bucket, "my-sharedworld-bucket", "text", ""),
        access_key = field("access_key_id", "Access key id", &fields.access_key_id, "", "text", ""),
        secret = field(
            "secret_access_key",
            "Secret access key",
            "",
            "",
            "password",
            "Needs read/write permission on the bucket.",
        ),
        prefix = field(
            "key_prefix",
            "Key prefix (optional)",
            &fields.key_prefix,
            "sharedworld/",
            "text",
            "Folder inside the bucket. Empty = sharedworld/, a single / = the bucket root.",
        ),
    );
    render_storage_form_page(200, &body)
}

/// The form shell: same look as the result page, plus form styles.
fn render_storage_form_page(status: u16, inner: &str) -> Response {
    let html = format!(
        r#"<!doctype html>
<html lang="en">
  <head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>SharedWorld</title>
    <style>
      :root {{ color-scheme: light; }}
      * {{ box-sizing: border-box; }}
      body {{
        margin: 0;
        min-height: 100vh;
        font-family: "Avenir Next", "Segoe UI", sans-serif;
        background:
          radial-gradient(circle at top, rgba(123, 161, 144, 0.16), transparent 36%),
          linear-gradient(180deg, #eef3f1 0%, #f6f1e9 100%);
        color: #1f2933;
      }}
      main {{ min-height: 100vh; display: grid; place-items: center; padding: 24px; }}
      .shell {{ width: min(100%, 560px); }}
      .brand {{
        margin: 0 0 16px; text-align: center; font-size: 0.72rem; font-weight: 600;
        letter-spacing: 0.18em; text-transform: uppercase; color: #6d7c76;
      }}
      .card {{
        border-radius: 24px; border: 1px solid rgba(95, 111, 104, 0.14);
        background: rgba(255, 255, 255, 0.82);
        box-shadow: 0 24px 60px rgba(31, 41, 51, 0.1);
        padding: 36px 32px 32px; backdrop-filter: blur(8px);
      }}
      h1 {{ margin: 0; font-size: clamp(1.6rem, 4vw, 2rem); line-height: 1.1; color: #14212b; }}
      .message {{ margin: 14px 0 0; font-size: 0.95rem; line-height: 1.55; color: #556471; }}
      form {{ margin-top: 20px; display: grid; gap: 14px; }}
      .field {{ display: grid; gap: 4px; }}
      .field-label {{ font-size: 0.82rem; font-weight: 600; color: #3c4a55; }}
      .field-hint {{ font-size: 0.76rem; color: #7b8894; }}
      input[type="text"], input[type="password"] {{
        width: 100%; padding: 10px 12px; font-size: 0.95rem;
        border: 1px solid rgba(95, 111, 104, 0.3); border-radius: 10px;
        background: rgba(255, 255, 255, 0.9); color: #1f2933;
      }}
      input:focus {{ outline: 2px solid rgba(92, 127, 104, 0.45); border-color: transparent; }}
      button {{
        margin-top: 6px; padding: 12px 16px; font-size: 1rem; font-weight: 600;
        color: #f6f1e9; background: #2f4a3c; border: none; border-radius: 12px; cursor: pointer;
      }}
      button:hover {{ background: #3a5a49; }}
      @media (max-width: 640px) {{ .card {{ padding: 28px 24px 24px; border-radius: 20px; }} }}
    </style>
  </head>
  <body>
    <main>
      <div class="shell">
        <p class="brand">SharedWorld</p>
        <section class="card">
          {inner}
        </section>
      </div>
    </main>
  </body>
</html>"#
    );
    let mut resp = Response::new(axum::body::Body::from(html));
    *resp.status_mut() = StatusCode::from_u16(status).unwrap_or(StatusCode::INTERNAL_SERVER_ERROR);
    resp.headers_mut().insert(header::CONTENT_TYPE, HeaderValue::from_static("text/html; charset=utf-8"));
    resp
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
