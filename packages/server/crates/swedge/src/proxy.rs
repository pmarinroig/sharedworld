//! HTTP reverse proxy to the core over its Unix socket: a pooled HTTP/1.1
//! client (hyper-util legacy pool over a Unix-socket connector), streaming
//! bodies both ways, gated on the core link so a restarting core re-attaches
//! its sockets before serving, with connects retried for up to 10 s.

use std::path::PathBuf;
use std::sync::Arc;
use std::time::{Duration, Instant};

use axum::body::Body;
use axum::extract::{ConnectInfo, Request, State};
use axum::http::{header, HeaderValue, StatusCode};
use axum::response::{IntoResponse, Response};
use hyper_util::client::legacy::connect::{Connected, Connection};
use hyper_util::client::legacy::Client;
use hyper_util::rt::{TokioExecutor, TokioIo};
use metrics::counter;
use tokio::net::UnixStream;

/// hyper-util pool connector that ignores the URI authority and dials the
/// core's Unix socket (retrying while the core restarts).
#[derive(Clone)]
pub struct UdsConnector {
    path: Arc<PathBuf>,
    retry_for: Duration,
}

pub struct UdsIo(TokioIo<UnixStream>);

impl Connection for UdsIo {
    fn connected(&self) -> Connected {
        Connected::new()
    }
}

impl hyper::rt::Read for UdsIo {
    fn poll_read(
        self: std::pin::Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
        buf: hyper::rt::ReadBufCursor<'_>,
    ) -> std::task::Poll<std::io::Result<()>> {
        std::pin::Pin::new(&mut self.get_mut().0).poll_read(cx, buf)
    }
}

impl hyper::rt::Write for UdsIo {
    fn poll_write(
        self: std::pin::Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
        buf: &[u8],
    ) -> std::task::Poll<std::io::Result<usize>> {
        std::pin::Pin::new(&mut self.get_mut().0).poll_write(cx, buf)
    }
    fn poll_flush(
        self: std::pin::Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
    ) -> std::task::Poll<std::io::Result<()>> {
        std::pin::Pin::new(&mut self.get_mut().0).poll_flush(cx)
    }
    fn poll_shutdown(
        self: std::pin::Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
    ) -> std::task::Poll<std::io::Result<()>> {
        std::pin::Pin::new(&mut self.get_mut().0).poll_shutdown(cx)
    }
    fn is_write_vectored(&self) -> bool {
        self.0.is_write_vectored()
    }
    fn poll_write_vectored(
        self: std::pin::Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
        bufs: &[std::io::IoSlice<'_>],
    ) -> std::task::Poll<std::io::Result<usize>> {
        std::pin::Pin::new(&mut self.get_mut().0).poll_write_vectored(cx, bufs)
    }
}

impl tower_service::Service<hyper::Uri> for UdsConnector {
    type Response = UdsIo;
    type Error = std::io::Error;
    type Future = std::pin::Pin<Box<dyn std::future::Future<Output = Result<UdsIo, std::io::Error>> + Send>>;

    fn poll_ready(&mut self, _cx: &mut std::task::Context<'_>) -> std::task::Poll<Result<(), Self::Error>> {
        std::task::Poll::Ready(Ok(()))
    }

    fn call(&mut self, _uri: hyper::Uri) -> Self::Future {
        let path = self.path.clone();
        let retry_for = self.retry_for;
        Box::pin(async move {
            match connect_with_retry(&path, retry_for).await {
                Some(stream) => Ok(UdsIo(TokioIo::new(stream))),
                None => {
                    Err(std::io::Error::new(std::io::ErrorKind::ConnectionRefused, "core socket unavailable"))
                }
            }
        })
    }
}

pub type CoreClient = Client<UdsConnector, Body>;

pub fn build_client(path: PathBuf, retry_for: Duration) -> CoreClient {
    Client::builder(TokioExecutor::new())
        .pool_idle_timeout(Duration::from_secs(30))
        .pool_max_idle_per_host(64)
        .build(UdsConnector { path: Arc::new(path), retry_for })
}

pub struct ProxyState {
    pub client: CoreClient,
    pub connect_retry_for: Duration,
    /// Gate: HTTP is forwarded only while the WS link is up (replay acked),
    /// so a restarting core re-attaches every socket before it serves.
    pub link: Arc<crate::corelink::CoreLink>,
    /// What the client spoke to this listener ("https" for the ACME listener,
    /// "http" for `--plain-listen`); the core builds signed URLs from it.
    pub forwarded_proto: &'static str,
}

fn unavailable() -> Response {
    let mut resp = (
        StatusCode::SERVICE_UNAVAILABLE,
        r#"{"error":"service_unavailable","message":"The server is restarting. Please retry.","status":503}"#,
    )
        .into_response();
    resp.headers_mut().insert(header::RETRY_AFTER, HeaderValue::from_static("2"));
    resp.headers_mut()
        .insert(header::CONTENT_TYPE, HeaderValue::from_static("application/json; charset=utf-8"));
    resp
}

async fn connect_with_retry(path: &std::path::Path, budget: Duration) -> Option<UnixStream> {
    let deadline = Instant::now() + budget;
    loop {
        match UnixStream::connect(path).await {
            Ok(s) => return Some(s),
            Err(_) if Instant::now() < deadline => tokio::time::sleep(Duration::from_millis(100)).await,
            Err(_) => return None,
        }
    }
}

pub async fn proxy(
    State(state): State<Arc<ProxyState>>,
    ConnectInfo(peer): ConnectInfo<std::net::SocketAddr>,
    mut req: Request,
) -> Response {
    // Hop-by-hop cleanup + client ip for the core's logs.
    let headers = req.headers_mut();
    headers.remove(header::CONNECTION);
    headers.remove("keep-alive");
    headers.remove(header::PROXY_AUTHENTICATE);
    headers.remove(header::PROXY_AUTHORIZATION);
    headers.remove(header::TE);
    headers.remove(header::TRAILER);
    headers.remove(header::TRANSFER_ENCODING);
    headers.remove(header::UPGRADE);
    if let Ok(v) = HeaderValue::from_str(&peer.ip().to_string()) {
        headers.insert("x-forwarded-for", v);
    }
    headers.insert("x-forwarded-proto", HeaderValue::from_static(state.forwarded_proto));
    // The core speaks HTTP/1.1 over the socket; an h2 client request must be
    // downgraded or the pool refuses it ("unsupported version").
    *req.version_mut() = axum::http::Version::HTTP_11;
    // The pool keys on the authority; the connector ignores it.
    let path_and_query =
        req.uri().path_and_query().map(|p| p.as_str().to_string()).unwrap_or_else(|| "/".into());
    *req.uri_mut() =
        format!("http://core{path_and_query}").parse().unwrap_or_else(|_| "http://core/".parse().unwrap());
    if !state.link.wait_connected(state.connect_retry_for).await {
        counter!("edge_proxy_unavailable_total").increment(1);
        return unavailable();
    }
    match state.client.request(req).await {
        Ok(resp) => {
            let (parts, body) = resp.into_parts();
            Response::from_parts(parts, Body::new(body))
        }
        Err(e) if e.is_connect() => {
            counter!("edge_proxy_unavailable_total").increment(1);
            unavailable()
        }
        Err(e) => {
            counter!("edge_proxy_errors_total").increment(1);
            tracing::warn!(error = %e, "proxy request failed");
            (
                StatusCode::BAD_GATEWAY,
                r#"{"error":"bad_gateway","message":"Upstream request failed.","status":502}"#,
            )
                .into_response()
        }
    }
}
