//! `HttpError` (`http.ts`): the one error type clients branch on.

use std::fmt;

use sw_contracts::ApiErrorShape;

#[derive(Debug, Clone, PartialEq)]
pub struct HttpError {
    pub status: u16,
    pub code: &'static str,
    pub message: String,
    pub retry_after_seconds: Option<u32>,
    pub reason: Option<&'static str>,
}

impl HttpError {
    pub fn new(status: u16, code: &'static str, message: impl Into<String>) -> Self {
        Self { status, code, message: message.into(), retry_after_seconds: None, reason: None }
    }
    pub fn with_reason(mut self, reason: &'static str) -> Self {
        self.reason = Some(reason);
        self
    }
    pub fn with_retry_after(mut self, seconds: u32) -> Self {
        self.retry_after_seconds = Some(seconds);
        self
    }
    pub fn internal(message: impl Into<String>) -> Self {
        Self::new(500, "internal_error", message)
    }
    pub fn shape(&self) -> ApiErrorShape {
        ApiErrorShape {
            error: self.code.to_string(),
            message: self.message.clone(),
            status: self.status,
            reason: self.reason.map(|r| r.to_string()),
        }
    }
}

impl fmt::Display for HttpError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{} {}: {}", self.status, self.code, self.message)
    }
}

impl std::error::Error for HttpError {}

impl From<sw_db::DbError> for HttpError {
    fn from(e: sw_db::DbError) -> Self {
        match e {
            sw_db::DbError::ManifestUnavailable(msg) => {
                HttpError::new(502, "snapshot_manifest_unavailable", msg)
            }
            other => {
                tracing::error!(error = %other, "SharedWorld database error");
                HttpError::internal("Internal server error.")
            }
        }
    }
}

pub type HttpResult<T> = Result<T, HttpError>;
