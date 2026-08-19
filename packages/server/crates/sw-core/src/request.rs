//! `RequestContext` (`repository.ts`): the authenticated caller plus the
//! request facts services branch on.

use std::future::Future;
use std::pin::Pin;
use std::sync::Arc;

use sw_db::repo::Actor;

pub type BoxFuture = Pin<Box<dyn Future<Output = ()> + Send + 'static>>;

/// Runs housekeeping AFTER the response is sent (`ctx.waitUntil`). `None`
/// means callers await the work inline (tests, tools).
pub type Deferrer = Arc<dyn Fn(BoxFuture) + Send + Sync>;

#[derive(Clone, Default)]
pub struct RequestContext {
    pub player_uuid: String,
    pub player_name: String,
    /// Origin of the request as the client addressed it (`x-sw-entry-origin`
    /// from the forwarder, else the request's own origin).
    pub request_origin: Option<String>,
    /// `x-sharedworld-version` (0.2.2+ clients).
    pub client_version: Option<String>,
    pub defer: Option<Deferrer>,
}

impl std::fmt::Debug for RequestContext {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("RequestContext")
            .field("player_uuid", &self.player_uuid)
            .field("player_name", &self.player_name)
            .field("request_origin", &self.request_origin)
            .field("client_version", &self.client_version)
            .finish()
    }
}

impl RequestContext {
    pub fn new(player_uuid: impl Into<String>, player_name: impl Into<String>) -> Self {
        Self { player_uuid: player_uuid.into(), player_name: player_name.into(), ..Default::default() }
    }

    pub fn actor(&self) -> Actor {
        Actor { player_uuid: self.player_uuid.clone(), player_name: self.player_name.clone() }
    }

    /// `clientVersionAtLeast`: fails toward "old client".
    pub fn client_at_least(&self, major: u32, minor: u32, patch: u32) -> bool {
        client_version_at_least(self.client_version.as_deref(), major, minor, patch)
    }

    /// Run `task` after the response (or inline when no deferrer is installed).
    pub async fn run_after_response<F>(&self, task: F)
    where
        F: Future<Output = ()> + Send + 'static,
    {
        match &self.defer {
            Some(defer) => defer(Box::pin(task)),
            None => task.await,
        }
    }
}

pub fn client_version_at_least(client_version: Option<&str>, major: u32, minor: u32, patch: u32) -> bool {
    let Some(v) = client_version else { return false };
    let mut parts = [0u32; 3];
    let mut rest = v;
    for (i, slot) in parts.iter_mut().enumerate() {
        let digits: String = rest.chars().take_while(|c| c.is_ascii_digit()).collect();
        if digits.is_empty() {
            return false;
        }
        let Ok(n) = digits.parse::<u32>() else { return false };
        *slot = n;
        rest = &rest[digits.len()..];
        if i < 2 {
            if !rest.starts_with('.') {
                return false;
            }
            rest = &rest[1..];
        }
    }
    let wanted = [major, minor, patch];
    for i in 0..3 {
        if parts[i] != wanted[i] {
            return parts[i] > wanted[i];
        }
    }
    true
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn version_gate_matches_ts() {
        assert!(client_version_at_least(Some("0.4.1"), 0, 4, 1));
        assert!(client_version_at_least(Some("0.4.1+mc1.21.11"), 0, 4, 0));
        assert!(!client_version_at_least(Some("0.3.9"), 0, 4, 0));
        assert!(!client_version_at_least(Some("unknown"), 0, 0, 0));
        assert!(!client_version_at_least(None, 0, 0, 0));
        assert!(client_version_at_least(Some("1.0.0"), 0, 9, 9));
    }
}
