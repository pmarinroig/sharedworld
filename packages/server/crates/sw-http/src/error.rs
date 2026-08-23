//! `errorResponse` (`http.ts`): `HttpError` → `ApiErrorShape` JSON + optional
//! `Retry-After`; the logging lines live in the middleware (`app.rs`) where
//! the route is known.

use axum::http::{header, HeaderValue, StatusCode};
use axum::response::{IntoResponse, Response};
use sw_core::HttpError;

/// Newtype so `HttpError` (foreign) can implement `IntoResponse`.
#[derive(Debug)]
pub struct ApiError(pub HttpError);

impl From<HttpError> for ApiError {
    fn from(e: HttpError) -> Self {
        ApiError(e)
    }
}

impl From<sw_db::DbError> for ApiError {
    fn from(e: sw_db::DbError) -> Self {
        ApiError(HttpError::from(e))
    }
}

/// Attached to error responses so the logging middleware can see the code.
#[derive(Debug, Clone)]
pub struct ErrorInfo {
    pub code: &'static str,
    pub status: u16,
    pub message: String,
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        let e = self.0;
        let body = serde_json::to_vec(&e.shape()).expect("json");
        let mut resp = Response::new(axum::body::Body::from(body));
        *resp.status_mut() = StatusCode::from_u16(e.status).unwrap_or(StatusCode::INTERNAL_SERVER_ERROR);
        resp.headers_mut()
            .insert(header::CONTENT_TYPE, HeaderValue::from_static("application/json; charset=utf-8"));
        if let Some(secs) = e.retry_after_seconds {
            if let Ok(v) = HeaderValue::from_str(&secs.to_string()) {
                resp.headers_mut().insert(header::RETRY_AFTER, v);
            }
        }
        resp.extensions_mut().insert(ErrorInfo { code: e.code, status: e.status, message: e.message });
        resp
    }
}

pub type ApiResult<T> = Result<T, ApiError>;

/// `json(data)`; JSON body with the worker's content-type.
pub fn json_response<T: serde::Serialize>(status: StatusCode, value: &T) -> Response {
    let body = serde_json::to_vec(value).expect("json");
    let mut resp = Response::new(axum::body::Body::from(body));
    *resp.status_mut() = status;
    resp.headers_mut()
        .insert(header::CONTENT_TYPE, HeaderValue::from_static("application/json; charset=utf-8"));
    resp
}

pub fn ok_json<T: serde::Serialize>(value: &T) -> Response {
    json_response(StatusCode::OK, value)
}

/// `ok()`; 204.
pub fn no_content() -> Response {
    StatusCode::NO_CONTENT.into_response()
}
