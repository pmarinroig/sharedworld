//! `readJson` extractor: any parse failure is `400 invalid_json`.

use axum::extract::{FromRequest, Request};
use axum::http::StatusCode;
use sw_core::HttpError;

use crate::error::ApiError;

pub struct JsonBody<T>(pub T);

/// Bodies the worker read with `request.json()`; larger is a client bug.
const MAX_JSON_BODY: usize = 64 * 1024 * 1024;

impl<S, T> FromRequest<S> for JsonBody<T>
where
    S: Send + Sync,
    T: serde::de::DeserializeOwned,
{
    type Rejection = ApiError;

    async fn from_request(req: Request, _state: &S) -> Result<Self, Self::Rejection> {
        let bytes = axum::body::to_bytes(req.into_body(), MAX_JSON_BODY).await.map_err(|_| {
            ApiError(HttpError::new(
                StatusCode::BAD_REQUEST.as_u16(),
                "invalid_json",
                "Request body must be valid JSON.",
            ))
        })?;
        serde_json::from_slice::<T>(&bytes)
            .map(JsonBody)
            .map_err(|_| ApiError(HttpError::new(400, "invalid_json", "Request body must be valid JSON.")))
    }
}
