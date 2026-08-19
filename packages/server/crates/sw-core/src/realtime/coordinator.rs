//! The per-world runtime authority (`realtime/coordinator.ts`). One
//! instance per world, serialized by the actor; every session/election/
//! lease decision funnels through here. Invariants I1–I8 / P1–P9 in
//! `docs/protocol.md`.

use std::sync::Arc;

use sw_contracts::{
    FinalizationActionResult, HostStartupProgress, RealtimeEvent, RealtimeEventKind, RoomPlayer,
    StartupProgressMode, UncleanShutdownPhase, UncleanShutdownWarning, WorldRuntimePhase, WorldRuntimeStatus,
    WorldStatus, HANDOFF_WAITER_TIMEOUT_MS, HOST_LIVE_LEASE_TIMEOUT_MS, PLAYER_PRESENCE_TIMEOUT_MS,
};

use super::runtime_protocol::*;
use super::store::*;
use crate::http_error::{HttpError, HttpResult};
use crate::ids::random_id;
use crate::time::{self, Instant};

/// Grace window between the host's socket dropping and lease forfeiture.
pub const HOST_DISCONNECT_GRACE_MS: i64 = 30_000;
/// How long the store-cached membership list may serve coordinator calls.
pub const MEMBERSHIP_CACHE_TTL_MS: i64 = 60_000;
/// How long expired legacy-presence entries (incl. tombstones) are retained.
pub const LEGACY_PRESENCE_RETENTION_MS: i64 = 10 * 60_000;
/// Socket-derived presence rides out short socket blips.
pub const PRESENCE_SOCKET_GRACE_MS: i64 = 15_000;
/// A waiter is electable only while its row was refreshed this recently.
pub const WAITER_ELECTION_FRESHNESS_MS: i64 = 20_000;

/// The caller's identity plus the membership facts already checked in SQL.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SessionActor {
    pub player_uuid: String,
    pub player_name: String,
    pub membership_active: bool,
    pub ever_member: bool,
}

#[derive(Debug, Clone, PartialEq)]
pub struct SessionEntryDecision {
    pub action: sw_contracts::EnterSessionAction,
    pub runtime: WorldRuntimeStatus,
    pub assignment: Option<sw_contracts::HostAssignment>,
    pub waiter_session_id: Option<String>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct WaitingObservation {
    pub action: sw_contracts::ObserveWaitingAction,
    pub runtime: WorldRuntimeStatus,
    pub waiter_session_id: Option<String>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct ReleaseHostResult {
    pub world_id: String,
    pub released_at: String,
    pub graceful: bool,
    pub next_host_uuid: Option<String>,
    pub next_host_player_name: Option<String>,
}

#[derive(Debug, Clone, Default)]
pub struct HostAuthorityArgs {
    pub runtime_epoch: Option<i64>,
    pub host_token: Option<String>,
}

#[derive(Debug, Clone, Default)]
pub struct HeartbeatArgs {
    pub runtime_epoch: Option<i64>,
    pub host_token: Option<String>,
    pub join_target: Option<String>,
    pub minecraft_version: Option<String>,
}

#[derive(Debug, Clone, Default)]
pub struct StartupProgressArgs {
    pub runtime_epoch: Option<i64>,
    pub host_token: Option<String>,
    pub label: Option<String>,
    pub mode: Option<StartupProgressMode>,
    pub fraction: Option<f64>,
}

#[derive(Debug, Clone)]
pub struct LegacyPresenceArgs {
    pub present: bool,
    pub guest_session_epoch: i64,
    pub presence_sequence: i64,
}

pub fn host_not_active_error(reason: Option<&'static str>) -> HttpError {
    let mut e = HttpError::new(
        409,
        "host_not_active",
        "Someone else is hosting this world now, so this upload was stopped.",
    );
    e.reason = reason;
    e
}

/// `lease_expired` (no runtime survives) vs `replaced` (another player holds
/// it); same-player mismatches stay reasonless.
fn host_not_active_reason(runtime: Option<&WorldRuntimeRecord>, player_uuid: &str) -> Option<&'static str> {
    match runtime {
        None => Some("lease_expired"),
        Some(r) if r.host_uuid.as_deref() != Some(player_uuid) => Some("replaced"),
        _ => None,
    }
}

pub struct WorldCoordinator {
    world_id: String,
    store: Box<dyn CoordinatorStore>,
    effects: Arc<dyn CoordinatorEffects>,
}

enum Immediate {
    Connect,
    CurrentHost(sw_contracts::HostAssignment),
}

fn immediate_entry_kind(resolved: &ResolvedRuntimeState, player_uuid: &str) -> Option<Immediate> {
    if runtime_allows_direct_connect(resolved) {
        return Some(Immediate::Connect);
    }
    host_assignment_for_current_runtime(resolved, player_uuid).map(Immediate::CurrentHost)
}

fn epoch_baseline(resolved: &ResolvedRuntimeState) -> Option<i64> {
    if let Some(r) = &resolved.runtime {
        return Some(r.runtime_epoch);
    }
    if let Some(w) = &resolved.warning {
        return Some(w.runtime_epoch);
    }
    resolved.retired_runtime_epoch
}

fn candidate_from_runtime(runtime: &WorldRuntimeRecord) -> Option<RuntimeCandidate> {
    let (Some(c), Some(h), Some(name)) =
        (&runtime.candidate_uuid, &runtime.host_uuid, &runtime.host_player_name)
    else {
        return None;
    };
    if c != h {
        return None;
    }
    Some(RuntimeCandidate { player_uuid: c.clone(), player_name: name.clone() })
}

fn finalization_result(
    world_id: &str,
    runtime: Option<&WorldRuntimeRecord>,
    candidate: Option<&RuntimeCandidate>,
) -> FinalizationActionResult {
    let status = to_runtime_status(world_id, runtime, candidate, None);
    FinalizationActionResult {
        world_id: world_id.to_string(),
        next_host_uuid: status.candidate_uuid,
        next_host_player_name: status.candidate_player_name,
        status: match runtime {
            Some(r) => r.phase.world_status(),
            None if candidate.is_some() => WorldStatus::Handoff,
            None => WorldStatus::Idle,
        },
    }
}

fn sanitize_waiter_session_id(id: Option<&str>) -> Option<String> {
    let t = id?.trim();
    if t.is_empty() {
        None
    } else {
        Some(t.to_string())
    }
}

fn clamp_fraction(value: Option<f64>) -> Option<f64> {
    let v = value?;
    if !v.is_finite() {
        return None;
    }
    Some(v.clamp(0.0, 1.0))
}

fn lease_deadline_passed(runtime: &WorldRuntimeRecord, now: Instant) -> bool {
    phase_deadline(runtime).is_some_and(|d| d <= now)
}

fn parse(s: &str) -> Option<Instant> {
    time::parse_iso(s)
}

fn ms(t: Instant) -> i64 {
    time::to_millis(t)
}

/// Drop per-request churn (updatedAt) so heartbeats do not spam events.
fn status_fingerprint(status: &WorldRuntimeStatus) -> String {
    let mut v = serde_json::to_value(status).expect("status serializes");
    if let Some(obj) = v.as_object_mut() {
        obj.remove("updatedAt");
    }
    serde_json::to_string(&v).expect("json")
}

impl WorldCoordinator {
    pub fn new(
        world_id: impl Into<String>,
        store: Box<dyn CoordinatorStore>,
        effects: Arc<dyn CoordinatorEffects>,
    ) -> Self {
        Self { world_id: world_id.into(), store, effects }
    }

    pub fn world_id(&self) -> &str {
        &self.world_id
    }

    pub fn store_mut(&mut self) -> &mut dyn CoordinatorStore {
        self.store.as_mut()
    }

    pub fn store(&self) -> &dyn CoordinatorStore {
        self.store.as_ref()
    }

    // ---------------------------------------------------------------- entry

    pub async fn enter_session(
        &mut self,
        actor: &SessionActor,
        waiter_session_id: Option<&str>,
        acknowledge_unclean_shutdown: bool,
        now: Instant,
    ) -> HttpResult<SessionEntryDecision> {
        self.require_session_access(actor, false)?;
        let requested = sanitize_waiter_session_id(waiter_session_id);
        let resolved = self.resolve(now).await?;

        if let Some(immediate) = immediate_entry_kind(&resolved, &actor.player_uuid) {
            if let Some(id) = &requested {
                self.cancel_waiter_session_internal(&actor.player_uuid, id);
            }
            return match immediate {
                Immediate::Connect => {
                    self.respond_entry(
                        sw_contracts::EnterSessionAction::Connect,
                        &resolved,
                        resolved.runtime.as_ref(),
                        None,
                        None,
                        now,
                    )
                    .await
                }
                Immediate::CurrentHost(a) => {
                    self.respond_entry(
                        sw_contracts::EnterSessionAction::Host,
                        &resolved,
                        resolved.runtime.as_ref(),
                        Some(a),
                        None,
                        now,
                    )
                    .await
                }
            };
        }
        if resolved.runtime.is_none() && resolved.candidate.is_none() {
            if resolved.warning.is_some() && !acknowledge_unclean_shutdown {
                return self
                    .respond_entry(
                        sw_contracts::EnterSessionAction::WarnHost,
                        &resolved,
                        None,
                        None,
                        None,
                        now,
                    )
                    .await;
            }
            let claimed = self
                .claim_host(
                    &RuntimeCandidate {
                        player_uuid: actor.player_uuid.clone(),
                        player_name: actor.player_name.clone(),
                    },
                    &resolved,
                    now,
                )
                .await;
            if let Some(id) = &requested {
                self.cancel_waiter_session_internal(&actor.player_uuid, id);
            }
            return self
                .respond_entry(
                    sw_contracts::EnterSessionAction::Host,
                    &resolved,
                    Some(&claimed.runtime),
                    Some(claimed.assignment),
                    None,
                    now,
                )
                .await;
        }
        let (waiter_id, active) = self.register_waiter(actor, requested.as_deref(), now);
        let reresolved = self.resolve(now).await?;
        let reported = if active { Some(waiter_id.clone()) } else { None };
        if runtime_requires_waiting(&reresolved) {
            return self
                .respond_entry(
                    sw_contracts::EnterSessionAction::Wait,
                    &reresolved,
                    reresolved.runtime.as_ref(),
                    None,
                    reported,
                    now,
                )
                .await;
        }
        if active
            && reresolved.runtime.is_none()
            && reresolved.candidate.as_ref().is_some_and(|c| c.player_uuid == actor.player_uuid)
        {
            let candidate = reresolved.candidate.clone().unwrap();
            let promoted = self.claim_host(&candidate, &reresolved, now).await;
            self.cancel_waiter_session_internal(&actor.player_uuid, &waiter_id);
            return self
                .respond_entry(
                    sw_contracts::EnterSessionAction::Host,
                    &reresolved,
                    Some(&promoted.runtime),
                    Some(promoted.assignment),
                    None,
                    now,
                )
                .await;
        }
        self.respond_entry(
            sw_contracts::EnterSessionAction::Wait,
            &reresolved,
            reresolved.runtime.as_ref(),
            None,
            reported,
            now,
        )
        .await
    }

    async fn respond_entry(
        &mut self,
        action: sw_contracts::EnterSessionAction,
        state: &ResolvedRuntimeState,
        runtime: Option<&WorldRuntimeRecord>,
        assignment: Option<sw_contracts::HostAssignment>,
        waiter_session_id: Option<String>,
        now: Instant,
    ) -> HttpResult<SessionEntryDecision> {
        let runtime = runtime.cloned();
        self.after_state_change(now).await?;
        Ok(SessionEntryDecision {
            action,
            runtime: to_runtime_status(
                &self.world_id,
                runtime.as_ref(),
                state.candidate.as_ref(),
                state.warning.as_ref(),
            ),
            assignment,
            waiter_session_id,
        })
    }

    pub async fn observe_waiting(
        &mut self,
        actor: &SessionActor,
        waiter_session_id: Option<&str>,
        now: Instant,
    ) -> HttpResult<WaitingObservation> {
        use sw_contracts::ObserveWaitingAction as A;
        self.require_session_access(actor, false)?;
        let Some(waiter_session_id) = sanitize_waiter_session_id(waiter_session_id) else {
            return Err(HttpError::new(
                400,
                "invalid_waiter_session",
                "SharedWorld waiting session id is required.",
            ));
        };
        let waiter_active = self.refresh_waiter_session(actor, &waiter_session_id, now);
        let resolved = self.resolve(now).await?;

        let immediate = immediate_entry_kind(&resolved, &actor.player_uuid);
        if let Some(immediate) = immediate {
            if waiter_active {
                self.cancel_waiter_session_internal(&actor.player_uuid, &waiter_session_id);
            }
            let action = if matches!(immediate, Immediate::Connect) { A::Connect } else { A::Restart };
            return self.respond_observe(action, &resolved, resolved.runtime.clone(), None, now).await;
        }
        if !waiter_active {
            return self.respond_observe(A::Restart, &resolved, resolved.runtime.clone(), None, now).await;
        }
        if resolved.runtime.is_none()
            && resolved.candidate.as_ref().is_some_and(|c| c.player_uuid == actor.player_uuid)
        {
            let candidate = resolved.candidate.clone().unwrap();
            let promoted = self.claim_host(&candidate, &resolved, now).await;
            self.cancel_waiter_session_internal(&actor.player_uuid, &waiter_session_id);
            return self.respond_observe(A::Restart, &resolved, Some(promoted.runtime), None, now).await;
        }
        if resolved.runtime.is_none() {
            return if resolved.candidate.as_ref().is_some_and(|c| c.player_uuid != actor.player_uuid) {
                self.respond_observe(A::Wait, &resolved, None, Some(waiter_session_id), now).await
            } else {
                self.respond_observe(A::Restart, &resolved, None, None, now).await
            };
        }
        self.respond_observe(A::Wait, &resolved, resolved.runtime.clone(), Some(waiter_session_id), now).await
    }

    async fn respond_observe(
        &mut self,
        action: sw_contracts::ObserveWaitingAction,
        resolved: &ResolvedRuntimeState,
        runtime: Option<WorldRuntimeRecord>,
        waiter_session_id: Option<String>,
        now: Instant,
    ) -> HttpResult<WaitingObservation> {
        self.after_state_change(now).await?;
        Ok(WaitingObservation {
            action,
            runtime: to_runtime_status(
                &self.world_id,
                runtime.as_ref(),
                resolved.candidate.as_ref(),
                resolved.warning.as_ref(),
            ),
            waiter_session_id,
        })
    }

    pub async fn runtime_status(
        &mut self,
        actor: &SessionActor,
        now: Instant,
    ) -> HttpResult<WorldRuntimeStatus> {
        self.require_session_access(actor, true)?;
        let resolved = self.resolve(now).await?;
        self.after_state_change(now).await?;
        Ok(to_runtime_status(
            &self.world_id,
            resolved.runtime.as_ref(),
            resolved.candidate.as_ref(),
            resolved.warning.as_ref(),
        ))
    }

    pub async fn cancel_waiting(
        &mut self,
        actor: &SessionActor,
        waiter_session_id: &str,
        now: Instant,
    ) -> HttpResult<WorldRuntimeStatus> {
        self.require_session_access(actor, false)?;
        self.cancel_waiter_session_internal(&actor.player_uuid, waiter_session_id);
        let resolved = self.resolve(now).await?;
        self.after_state_change(now).await?;
        Ok(to_runtime_status(
            &self.world_id,
            resolved.runtime.as_ref(),
            resolved.candidate.as_ref(),
            resolved.warning.as_ref(),
        ))
    }

    // ------------------------------------------------------------- host ops

    pub async fn heartbeat(
        &mut self,
        actor: &SessionActor,
        request: &HeartbeatArgs,
        now: Instant,
    ) -> HttpResult<WorldRuntimeStatus> {
        self.require_session_access(actor, false)?;
        if request.runtime_epoch.is_none_or(|e| e < 0) || request.host_token.is_none() {
            return Err(host_not_active_error(None));
        }
        let resolved = self.resolve(now).await?;
        let runtime = resolved.runtime.clone();
        let Some(runtime) = runtime.filter(|r| {
            matches_host_authorization(
                Some(r),
                &actor.player_uuid,
                request.runtime_epoch,
                request.host_token.as_deref(),
            )
        }) else {
            return Err(host_not_active_error(host_not_active_reason(
                resolved.runtime.as_ref(),
                &actor.player_uuid,
            )));
        };
        // The host just proved itself reachable over HTTPS; clear an armed
        // socket-grace deadline (connected state belongs to the gateway).
        let link = self.store.get_host_link();
        if link.grace_deadline_at.is_some() {
            self.store.set_host_link(&HostLink { connected: link.connected, grace_deadline_at: None });
        }
        if runtime.phase == WorldRuntimePhase::HostFinalizing {
            self.after_state_change(now).await?;
            return Ok(to_runtime_status(&self.world_id, Some(&runtime), resolved.candidate.as_ref(), None));
        }
        if !matches!(runtime.phase, WorldRuntimePhase::HostStarting | WorldRuntimePhase::HostLive) {
            return Err(host_not_active_error(None));
        }
        let mut updated = refresh_live_runtime(&runtime, request.join_target.as_deref(), now);
        if let Some(v) = request.minecraft_version.as_deref().map(str::trim).filter(|v| !v.is_empty()) {
            updated.host_minecraft_version = Some(v.to_string());
        }
        self.store.put_runtime(&updated);
        self.after_state_change(now).await?;
        let candidate = candidate_from_runtime(&updated);
        Ok(to_runtime_status(&self.world_id, Some(&updated), candidate.as_ref(), None))
    }

    pub async fn set_startup_progress(
        &mut self,
        actor: &SessionActor,
        request: &StartupProgressArgs,
        now: Instant,
    ) -> HttpResult<WorldRuntimeStatus> {
        self.require_session_access(actor, true)?;
        if request.runtime_epoch.is_none_or(|e| e < 0) || request.host_token.is_none() {
            return Err(host_not_active_error(None));
        }
        let runtime = self
            .require_authorized_runtime(
                actor,
                request.runtime_epoch,
                request.host_token.as_deref(),
                &[WorldRuntimePhase::HostStarting, WorldRuntimePhase::HostFinalizing],
                now,
            )
            .await?;
        let progress = match (&request.label, request.mode) {
            (Some(label), Some(mode)) => Some(HostStartupProgress {
                label: label.clone(),
                mode,
                fraction: clamp_fraction(request.fraction),
                updated_at: time::to_iso(now),
            }),
            _ => None,
        };
        let updated = set_host_progress(&runtime, progress, now);
        self.store.put_runtime(&updated);
        self.after_state_change(now).await?;
        let candidate = candidate_from_runtime(&updated);
        Ok(to_runtime_status(&self.world_id, Some(&updated), candidate.as_ref(), None))
    }

    /// Access-only check for HTTP read paths (revoked-host exception honoured).
    pub fn assert_session_access(&self, actor: &SessionActor, allow_revoked_host: bool) -> HttpResult<()> {
        self.require_session_access(actor, allow_revoked_host)
    }

    /// Host-owned write authorization for HTTP paths outside the coordinator.
    pub async fn validate_host_authority(
        &mut self,
        actor: &SessionActor,
        runtime_epoch: Option<i64>,
        host_token: Option<&str>,
        allowed_phases: &[WorldRuntimePhase],
        now: Instant,
    ) -> HttpResult<()> {
        self.require_session_access(actor, true)?;
        if runtime_epoch.is_none_or(|e| e < 0) || host_token.is_none() {
            return Err(host_not_active_error(None));
        }
        self.require_authorized_runtime(actor, runtime_epoch, host_token, allowed_phases, now).await?;
        Ok(())
    }

    pub async fn begin_finalization(
        &mut self,
        actor: &SessionActor,
        request: &HostAuthorityArgs,
        now: Instant,
    ) -> HttpResult<FinalizationActionResult> {
        self.require_session_access(actor, true)?;
        let runtime = self
            .require_authorized_runtime(
                actor,
                request.runtime_epoch,
                request.host_token.as_deref(),
                &[
                    WorldRuntimePhase::HostStarting,
                    WorldRuntimePhase::HostLive,
                    WorldRuntimePhase::HostFinalizing,
                ],
                now,
            )
            .await?;
        if runtime.phase == WorldRuntimePhase::HostFinalizing {
            self.after_state_change(now).await?;
            return Ok(finalization_result(&self.world_id, Some(&runtime), None));
        }
        let updated = move_to_finalizing(&runtime, now);
        self.store.put_runtime(&updated);
        self.after_state_change(now).await?;
        Ok(finalization_result(&self.world_id, Some(&updated), None))
    }

    pub async fn complete_finalization(
        &mut self,
        actor: &SessionActor,
        request: &HostAuthorityArgs,
        now: Instant,
    ) -> HttpResult<FinalizationActionResult> {
        self.require_session_access(actor, true)?;
        let resolved = self.resolve(now).await?;
        let runtime = resolved.runtime.clone();
        match runtime.filter(|r| r.phase == WorldRuntimePhase::HostFinalizing) {
            None => {
                if self.is_released_epoch_replay(request.runtime_epoch, resolved.warning.as_ref()) {
                    self.after_state_change(now).await?;
                    return Ok(finalization_result(&self.world_id, None, resolved.candidate.as_ref()));
                }
                Err(HttpError::new(409, "not_finalizing", "SharedWorld is not currently finalizing."))
            }
            Some(runtime) => {
                if !matches_host_authorization(
                    Some(&runtime),
                    &actor.player_uuid,
                    request.runtime_epoch,
                    request.host_token.as_deref(),
                ) {
                    return Err(host_not_active_error(host_not_active_reason(
                        Some(&runtime),
                        &actor.player_uuid,
                    )));
                }
                self.retire_runtime(&runtime, now).await?;
                self.store.clear_warning();
                let after = self.resolve(now).await?;
                self.after_state_change(now).await?;
                Ok(finalization_result(&self.world_id, None, after.candidate.as_ref()))
            }
        }
    }

    /// Owner check happens in the service (SQL owns world ownership).
    pub async fn abandon_finalization(&mut self, now: Instant) -> HttpResult<FinalizationActionResult> {
        let resolved = self.resolve(now).await?;
        let current = resolved.runtime.clone();
        match current {
            Some(r) if r.phase == WorldRuntimePhase::HostFinalizing => {
                self.retire_runtime(&r, now).await?;
                let after = self.resolve(now).await?;
                self.after_state_change(now).await?;
                Ok(finalization_result(&self.world_id, None, after.candidate.as_ref()))
            }
            other => {
                self.after_state_change(now).await?;
                Ok(finalization_result(&self.world_id, other.as_ref(), resolved.candidate.as_ref()))
            }
        }
    }

    pub async fn release_host(
        &mut self,
        actor: &SessionActor,
        request: &HostAuthorityArgs,
        graceful: bool,
        now: Instant,
    ) -> HttpResult<ReleaseHostResult> {
        self.require_session_access(actor, true)?;
        let resolved = self.resolve(now).await?;
        let runtime = resolved.runtime.clone();
        let authorized = runtime.as_ref().is_some_and(|r| {
            matches!(
                r.phase,
                WorldRuntimePhase::HostStarting
                    | WorldRuntimePhase::HostLive
                    | WorldRuntimePhase::HostFinalizing
            ) && matches_host_authorization(
                Some(r),
                &actor.player_uuid,
                request.runtime_epoch,
                request.host_token.as_deref(),
            )
        });
        if !authorized {
            if self.is_released_epoch_replay(request.runtime_epoch, resolved.warning.as_ref()) {
                return self.release_result(graceful, &resolved, now).await;
            }
            return Err(host_not_active_error(host_not_active_reason(runtime.as_ref(), &actor.player_uuid)));
        }
        let runtime = runtime.unwrap();
        self.store.delete_waiter(&actor.player_uuid);
        self.retire_runtime(&runtime, now).await?;
        if graceful {
            self.store.clear_warning();
        }
        let after = self.resolve(now).await?;
        self.release_result(graceful, &after, now).await
    }

    // ------------------------------------------------------------- presence

    /// Host-reported full roster of the integrated server.
    pub async fn report_host_players(
        &mut self,
        player_uuid: &str,
        runtime_epoch: i64,
        players: Vec<RoomPlayer>,
        now: Instant,
    ) -> HttpResult<()> {
        let resolved = self.resolve(now).await?;
        let ok = resolved
            .runtime
            .as_ref()
            .is_some_and(|r| r.host_uuid.as_deref() == Some(player_uuid) && r.runtime_epoch == runtime_epoch);
        if !ok {
            return Ok(()); // stale or unauthorized report: drop silently
        }
        self.store.set_room_players(Some(&players));
        self.publish_presence(now).await?;
        self.after_state_change(now).await
    }

    /// Legacy 0.2.x presence self-report adapter.
    pub async fn report_legacy_presence(
        &mut self,
        actor: &SessionActor,
        request: &LegacyPresenceArgs,
        now: Instant,
    ) -> HttpResult<()> {
        self.require_session_access(actor, false)?;
        self.apply_legacy_presence(actor, request, now);
        self.publish_presence(now).await?;
        self.after_state_change(now).await
    }

    /// The merged 0.4.1+ guest beat: records presence AND answers with the
    /// resolved runtime status.
    pub async fn guest_heartbeat(
        &mut self,
        actor: &SessionActor,
        request: &LegacyPresenceArgs,
        now: Instant,
    ) -> HttpResult<WorldRuntimeStatus> {
        self.require_session_access(actor, false)?;
        self.apply_legacy_presence(actor, request, now);
        let resolved = self.resolve(now).await?;
        self.publish_presence(now).await?;
        self.after_state_change(now).await?;
        Ok(to_runtime_status(
            &self.world_id,
            resolved.runtime.as_ref(),
            resolved.candidate.as_ref(),
            resolved.warning.as_ref(),
        ))
    }

    fn apply_legacy_presence(&mut self, actor: &SessionActor, request: &LegacyPresenceArgs, now: Instant) {
        let existing =
            self.store.list_legacy_presence().into_iter().find(|e| e.player_uuid == actor.player_uuid);
        let accepted = match &existing {
            None => true,
            Some(e) => {
                request.guest_session_epoch > e.guest_session_epoch
                    || (request.guest_session_epoch == e.guest_session_epoch
                        && request.presence_sequence >= e.presence_sequence)
            }
        };
        if accepted {
            self.store.upsert_legacy_presence(&LegacyPresenceEntry {
                player_uuid: actor.player_uuid.clone(),
                player_name: actor.player_name.clone(),
                present: request.present,
                guest_session_epoch: request.guest_session_epoch,
                presence_sequence: request.presence_sequence,
                expires_at: time::plus_ms_iso(now, PLAYER_PRESENCE_TIMEOUT_MS),
            });
        }
    }

    /// A 0.4.1 client announced or withdrew world presence over its socket
    /// (membership-gated; no expiry while the socket is up).
    pub async fn report_socket_presence(
        &mut self,
        player_uuid: &str,
        present: bool,
        now: Instant,
    ) -> HttpResult<()> {
        if present {
            let members = self.memberships(now).await?;
            let Some(m) =
                members.into_iter().find(|m| m.player_uuid == player_uuid && m.deleted_at.is_none())
            else {
                return Ok(());
            };
            self.store.upsert_socket_presence(&SocketPresenceEntry {
                player_uuid: player_uuid.to_string(),
                player_name: m.player_name,
                grace_deadline_at: None,
            });
        } else {
            self.store.delete_socket_presence(player_uuid);
        }
        self.publish_presence(now).await?;
        self.after_state_change(now).await
    }

    /// The player's last gateway socket closed: arm a grace deadline, publish nothing.
    pub async fn presence_socket_closed(&mut self, player_uuid: &str, now: Instant) -> HttpResult<()> {
        let Some(entry) =
            self.store.list_socket_presence().into_iter().find(|e| e.player_uuid == player_uuid)
        else {
            return Ok(());
        };
        self.store.upsert_socket_presence(&SocketPresenceEntry {
            grace_deadline_at: Some(time::plus_ms_iso(now, PRESENCE_SOCKET_GRACE_MS)),
            ..entry
        });
        self.after_state_change(now).await
    }

    /// The effective room roster (host-reported wins; else socket ∪ legacy).
    pub fn room_players(&self, now: Instant) -> Vec<RoomPlayer> {
        if let Some(reported) = self.store.get_room_players() {
            return reported;
        }
        let mut out: Vec<RoomPlayer> = Vec::new();
        for e in self.store.list_socket_presence() {
            let live = match &e.grace_deadline_at {
                None => true,
                Some(g) => parse(g).is_some_and(|d| d > now),
            };
            if live && !out.iter().any(|p| p.player_uuid == e.player_uuid) {
                out.push(RoomPlayer { player_uuid: e.player_uuid, player_name: e.player_name });
            }
        }
        for e in self.store.list_legacy_presence() {
            if e.present
                && parse(&e.expires_at).is_some_and(|d| d > now)
                && !out.iter().any(|p| p.player_uuid == e.player_uuid)
            {
                out.push(RoomPlayer { player_uuid: e.player_uuid, player_name: e.player_name });
            }
        }
        out
    }

    // ------------------------------------------------------------- liveness

    pub async fn host_socket_connected(&mut self, player_uuid: &str, now: Instant) -> HttpResult<()> {
        let Some(runtime) = self.store.get_runtime() else { return Ok(()) };
        if runtime.host_uuid.as_deref() != Some(player_uuid) {
            return Ok(());
        }
        self.store.set_host_link(&HostLink { connected: true, grace_deadline_at: None });
        self.after_state_change(now).await
    }

    pub async fn host_socket_closed(&mut self, player_uuid: &str, now: Instant) -> HttpResult<()> {
        let Some(runtime) = self.store.get_runtime() else { return Ok(()) };
        if runtime.host_uuid.as_deref() != Some(player_uuid) {
            return Ok(());
        }
        if !matches!(runtime.phase, WorldRuntimePhase::HostStarting | WorldRuntimePhase::HostLive) {
            return Ok(());
        }
        self.store.set_host_link(&HostLink {
            connected: false,
            grace_deadline_at: Some(time::plus_ms_iso(now, HOST_DISCONNECT_GRACE_MS)),
        });
        self.after_state_change(now).await
    }

    /// Box-only: called once when a world's state is loaded from
    /// persistence (process start / idle eviction reload). Host watches live
    /// in the gateway's memory, so re-register the current host's watch and
    /// reconcile the link to the observed socket state. No publish/mirror.
    pub async fn on_loaded(&mut self, now: Instant) {
        let Some(runtime) = self.store.get_runtime() else { return };
        let Some(host) = runtime.host_uuid.clone() else { return };
        let connected = self.effects.set_host_watch(&self.world_id, &host, true).await;
        let link = self.store.get_host_link();
        if connected {
            if !link.connected || link.grace_deadline_at.is_some() {
                self.store.set_host_link(&HostLink { connected: true, grace_deadline_at: None });
            }
        } else if link.connected
            && matches!(runtime.phase, WorldRuntimePhase::HostStarting | WorldRuntimePhase::HostLive)
        {
            self.store.set_host_link(&HostLink {
                connected: false,
                grace_deadline_at: Some(time::plus_ms_iso(now, HOST_DISCONNECT_GRACE_MS)),
            });
        }
    }

    /// Box-only: after a core restart the gateway rebuilds from the edge's
    /// replay; reconcile the host link to the observed socket state without
    /// arming or cancelling grace as if an event had happened.
    pub async fn reconcile_host_link(&mut self, host_connected: bool, now: Instant) -> HttpResult<()> {
        let Some(runtime) = self.store.get_runtime() else { return Ok(()) };
        if runtime.host_uuid.is_none() {
            return Ok(());
        }
        let link = self.store.get_host_link();
        if host_connected {
            if !link.connected || link.grace_deadline_at.is_some() {
                self.store.set_host_link(&HostLink { connected: true, grace_deadline_at: None });
            }
        } else if link.connected
            && matches!(runtime.phase, WorldRuntimePhase::HostStarting | WorldRuntimePhase::HostLive)
        {
            self.store.set_host_link(&HostLink {
                connected: false,
                grace_deadline_at: Some(time::plus_ms_iso(now, HOST_DISCONNECT_GRACE_MS)),
            });
        }
        self.after_state_change(now).await
    }

    /// The single alarm handler.
    pub async fn on_alarm(&mut self, now: Instant) -> HttpResult<()> {
        if let Some(runtime) = self.store.get_runtime() {
            if matches!(runtime.phase, WorldRuntimePhase::HostStarting | WorldRuntimePhase::HostLive) {
                let link = self.store.get_host_link();
                let grace_due = link.grace_deadline_at.as_deref().and_then(parse).is_some_and(|d| d <= now);
                if grace_due || lease_deadline_passed(&runtime, now) {
                    let rescued = self.rescue_reachable_host(&runtime, now).await?;
                    if rescued.is_none() && grace_due {
                        self.expire_runtime(&runtime, now, false).await?;
                    }
                }
            }
        }
        self.prune_legacy_presence(now);
        self.prune_socket_presence(now);
        self.resolve(now).await?;
        self.publish_presence(now).await?;
        self.after_state_change(now).await
    }

    fn prune_socket_presence(&mut self, now: Instant) {
        for e in self.store.list_socket_presence() {
            if e.grace_deadline_at.as_deref().and_then(parse).is_some_and(|d| d <= now) {
                self.store.delete_socket_presence(&e.player_uuid);
            }
        }
    }

    fn prune_legacy_presence(&mut self, now: Instant) {
        let cutoff = ms(now) - LEGACY_PRESENCE_RETENTION_MS;
        for e in self.store.list_legacy_presence() {
            if parse(&e.expires_at).is_some_and(|d| ms(d) < cutoff) {
                self.store.delete_legacy_presence(&e.player_uuid);
            }
        }
    }

    // ------------------------------------------------------------ lifecycle

    /// P5: world deleted — drop every trace and tell the (former) members.
    pub async fn destroy_world(&mut self, recipients: Vec<String>) -> HttpResult<()> {
        if let Some(host) = self.store.get_runtime().and_then(|r| r.host_uuid) {
            self.effects.set_host_watch(&self.world_id, &host, false).await;
        }
        self.store.clear_all();
        self.effects.schedule_alarm(&self.world_id, None).await;
        self.effects
            .publish(
                RealtimeEvent {
                    world_id: self.world_id.clone(),
                    kind: RealtimeEventKind::WorldDeleted,
                    runtime: None,
                    room_players: None,
                },
                Some(recipients),
            )
            .await;
        Ok(())
    }

    pub async fn memberships_changed(&mut self, now: Instant) -> HttpResult<()> {
        self.store.clear_membership_cache();
        self.after_state_change(now).await
    }

    /// A member was kicked; if they host, mark the runtime revoked (P6).
    pub async fn member_revoked(&mut self, player_uuid: &str, now: Instant) -> HttpResult<()> {
        self.store.clear_membership_cache();
        if let Some(runtime) = self.store.get_runtime() {
            if runtime.host_uuid.as_deref() == Some(player_uuid) && runtime.revoked_at.is_none() {
                self.store.put_runtime(&WorldRuntimeRecord {
                    revoked_at: Some(time::to_iso(now)),
                    updated_at: time::to_iso(now),
                    ..runtime
                });
            }
        }
        self.store.delete_waiter(player_uuid);
        self.store.delete_legacy_presence(player_uuid);
        self.store.delete_socket_presence(player_uuid);
        self.publish_presence(now).await?;
        self.after_state_change(now).await
    }

    // ------------------------------------------------------------ internals

    fn require_session_access(&self, actor: &SessionActor, allow_revoked_host: bool) -> HttpResult<()> {
        if actor.membership_active {
            return Ok(());
        }
        if allow_revoked_host {
            if let Some(r) = self.store.get_runtime() {
                if r.host_uuid.as_deref() == Some(actor.player_uuid.as_str()) && r.revoked_at.is_some() {
                    return Ok(());
                }
            }
        }
        if !actor.ever_member {
            return Err(HttpError::new(
                403,
                "forbidden",
                "You do not have access to this SharedWorld server.",
            ));
        }
        Err(HttpError::new(403, "membership_revoked", "You were removed from this SharedWorld."))
    }

    async fn require_authorized_runtime(
        &mut self,
        actor: &SessionActor,
        runtime_epoch: Option<i64>,
        host_token: Option<&str>,
        allowed_phases: &[WorldRuntimePhase],
        now: Instant,
    ) -> HttpResult<WorldRuntimeRecord> {
        let resolved = self.resolve(now).await?;
        match &resolved.runtime {
            Some(r)
                if allowed_phases.contains(&r.phase)
                    && matches_host_authorization(Some(r), &actor.player_uuid, runtime_epoch, host_token) =>
            {
                Ok(r.clone())
            }
            other => Err(host_not_active_error(host_not_active_reason(other.as_ref(), &actor.player_uuid))),
        }
    }

    /// Apply timeout expiry and elect the preferred candidate before anything
    /// reasons about the runtime.
    async fn resolve(&mut self, now: Instant) -> HttpResult<ResolvedRuntimeState> {
        self.expire_waiters(now);
        let memberships = self.memberships(now).await?;
        let electable: Vec<RuntimeCandidate> = self
            .electable_waiters(now)
            .into_iter()
            .map(|w| RuntimeCandidate { player_uuid: w.player_uuid, player_name: w.player_name })
            .collect();
        let candidate = choose_preferred_candidate(&electable, &memberships);
        let mut before = self.store.get_runtime();
        if let Some(b) = &before {
            if matches!(b.phase, WorldRuntimePhase::HostStarting | WorldRuntimePhase::HostLive)
                && lease_deadline_passed(b, now)
            {
                if let Some(r) = self.rescue_reachable_host(b, now).await? {
                    before = Some(r);
                }
            }
        }
        let timeout_warning = timed_out_unclean_shutdown_warning(before.as_ref(), now);
        if let (Some(warning), Some(b)) = (timeout_warning, before.as_ref()) {
            let epoch = b.runtime_epoch;
            let b = b.clone();
            self.expire_runtime(&b, now, true).await?;
            return Ok(ResolvedRuntimeState {
                runtime: None,
                candidate: None,
                warning: Some(warning),
                retired_runtime_epoch: Some(epoch),
            });
        }
        let after_timeout = resolve_runtime_timeout(before.as_ref(), now);
        if let (Some(b), None) = (&before, &after_timeout) {
            let b = b.clone();
            self.retire_runtime(&b, now).await?;
        }
        let warning = self.store.get_warning();
        let retired_runtime_epoch = if after_timeout.is_none() {
            Some(
                before
                    .as_ref()
                    .map(|b| b.runtime_epoch)
                    .or_else(|| warning.as_ref().map(|w| w.runtime_epoch))
                    .unwrap_or_else(|| self.store.get_last_epoch()),
            )
        } else {
            None
        };
        Ok(ResolvedRuntimeState { runtime: after_timeout, candidate, warning, retired_runtime_epoch })
    }

    /// Verify with a probe before declaring the host gone; repair link state
    /// when reachable. `Ok(None)` = genuinely unreachable; `Err` propagates
    /// (renewal aborted, expiry skipped — the safe failure).
    async fn rescue_reachable_host(
        &mut self,
        runtime: &WorldRuntimeRecord,
        now: Instant,
    ) -> HttpResult<Option<WorldRuntimeRecord>> {
        let last_seen =
            self.effects.probe_host_reachability(runtime.host_uuid.as_deref().unwrap_or("")).await?;
        let reachable = last_seen.is_some_and(|t| ms(now) - ms(t) < HOST_LIVE_LEASE_TIMEOUT_MS);
        if !reachable {
            return Ok(None);
        }
        let refreshed = refresh_live_runtime(runtime, None, now);
        self.store.put_runtime(&refreshed);
        self.store.set_host_link(&HostLink { connected: true, grace_deadline_at: None });
        Ok(Some(refreshed))
    }

    async fn claim_host(
        &mut self,
        candidate: &RuntimeCandidate,
        resolved: &ResolvedRuntimeState,
        now: Instant,
    ) -> Assigned {
        let assigned =
            assign_host_starting(&self.world_id, candidate, epoch_baseline(resolved), now, random_id("rt"));
        self.store.put_runtime(&assigned.runtime);
        // lastEpoch moves only on retire — a live epoch must not look released.
        self.store.set_room_players(None);
        let connected = self.effects.set_host_watch(&self.world_id, &candidate.player_uuid, true).await;
        self.store.set_host_link(&HostLink { connected, grace_deadline_at: None });
        assigned
    }

    /// Delete the runtime record and advance the replay high-water mark.
    async fn retire_runtime(&mut self, runtime: &WorldRuntimeRecord, now: Instant) -> HttpResult<()> {
        self.store.delete_runtime();
        let last = self.store.get_last_epoch();
        self.store.set_last_epoch(last.max(runtime.runtime_epoch));
        self.store.set_host_link(&HostLink::default());
        self.store.set_room_players(None);
        self.store.clear_legacy_presence();
        self.store.clear_socket_presence();
        if let Some(host) = &runtime.host_uuid {
            self.effects.set_host_watch(&self.world_id, host, false).await;
        }
        self.publish_presence(now).await
    }

    /// Lease/grace forfeiture: retire + record the unclean-shutdown warning
    /// (a blown host-starting deadline stays warning-free).
    async fn expire_runtime(
        &mut self,
        runtime: &WorldRuntimeRecord,
        now: Instant,
        clear_waiters: bool,
    ) -> HttpResult<()> {
        let phase = match runtime.phase {
            WorldRuntimePhase::HostLive => Some(UncleanShutdownPhase::HostLive),
            WorldRuntimePhase::HostFinalizing => Some(UncleanShutdownPhase::HostFinalizing),
            _ => None,
        };
        if let (Some(phase), Some(host), Some(name)) = (phase, &runtime.host_uuid, &runtime.host_player_name)
        {
            self.store.set_warning(&UncleanShutdownWarning {
                host_uuid: host.clone(),
                host_player_name: name.clone(),
                phase,
                runtime_epoch: runtime.runtime_epoch,
                recorded_at: time::to_iso(now),
            });
        }
        self.retire_runtime(runtime, now).await?;
        if clear_waiters {
            self.store.clear_waiters();
        }
        Ok(())
    }

    fn register_waiter(
        &mut self,
        actor: &SessionActor,
        requested: Option<&str>,
        now: Instant,
    ) -> (String, bool) {
        if let Some(id) = requested {
            let active = self.refresh_waiter_session(actor, id, now);
            return (id.to_string(), active);
        }
        let id = random_id("wait");
        self.store.upsert_waiter(&RuntimeWaiter {
            player_uuid: actor.player_uuid.clone(),
            player_name: actor.player_name.clone(),
            waiter_session_id: id.clone(),
            waiting: true,
            updated_at: time::to_iso(now),
        });
        (id, true)
    }

    fn refresh_waiter_session(
        &mut self,
        actor: &SessionActor,
        waiter_session_id: &str,
        now: Instant,
    ) -> bool {
        let existing = self
            .store
            .list_waiters()
            .into_iter()
            .find(|w| w.player_uuid == actor.player_uuid && w.waiter_session_id == waiter_session_id);
        let Some(existing) = existing else { return false };
        self.store.upsert_waiter(&RuntimeWaiter {
            player_name: actor.player_name.clone(),
            waiting: true,
            updated_at: time::to_iso(now),
            ..existing
        });
        true
    }

    fn cancel_waiter_session_internal(&mut self, player_uuid: &str, waiter_session_id: &str) {
        if self
            .store
            .list_waiters()
            .iter()
            .any(|w| w.player_uuid == player_uuid && w.waiter_session_id == waiter_session_id)
        {
            self.store.delete_waiter(player_uuid);
        }
    }

    fn electable_waiters(&self, now: Instant) -> Vec<RuntimeWaiter> {
        let fresh_cutoff = ms(now) - WAITER_ELECTION_FRESHNESS_MS;
        self.store
            .list_waiters()
            .into_iter()
            .filter(|w| w.waiting && parse(&w.updated_at).is_some_and(|t| ms(t) >= fresh_cutoff))
            .collect()
    }

    fn expire_waiters(&mut self, now: Instant) {
        let cutoff = ms(now) - HANDOFF_WAITER_TIMEOUT_MS;
        for w in self.store.list_waiters() {
            if parse(&w.updated_at).is_none_or(|t| ms(t) < cutoff) {
                self.store.delete_waiter(&w.player_uuid);
            }
        }
    }

    fn is_released_epoch_replay(
        &self,
        runtime_epoch: Option<i64>,
        warning: Option<&UncleanShutdownWarning>,
    ) -> bool {
        let Some(epoch) = runtime_epoch else { return false };
        if epoch < 1 {
            return false;
        }
        if warning.is_some_and(|w| w.runtime_epoch == epoch) {
            return false;
        }
        self.store.get_last_epoch() == epoch
    }

    async fn release_result(
        &mut self,
        graceful: bool,
        resolved: &ResolvedRuntimeState,
        now: Instant,
    ) -> HttpResult<ReleaseHostResult> {
        self.after_state_change(now).await?;
        let status = to_runtime_status(
            &self.world_id,
            resolved.runtime.as_ref(),
            resolved.candidate.as_ref(),
            resolved.warning.as_ref(),
        );
        Ok(ReleaseHostResult {
            world_id: self.world_id.clone(),
            released_at: time::to_iso(now),
            graceful,
            next_host_uuid: if graceful { status.candidate_uuid } else { None },
            next_host_player_name: if graceful { status.candidate_player_name } else { None },
        })
    }

    async fn memberships(&mut self, now: Instant) -> HttpResult<Vec<RuntimeMembership>> {
        if let Some(cached) = self.store.get_membership_cache() {
            if parse(&cached.fetched_at).is_some_and(|t| ms(now) - ms(t) < MEMBERSHIP_CACHE_TTL_MS) {
                return Ok(cached.members);
            }
        }
        let members = self.effects.list_memberships(&self.world_id).await?;
        self.store.set_membership_cache(&MembershipCache {
            members: members.clone(),
            fetched_at: time::to_iso(now),
        });
        Ok(members)
    }

    fn active_recipients(members: &[RuntimeMembership]) -> Vec<String> {
        members.iter().filter(|m| m.deleted_at.is_none()).map(|m| m.player_uuid.clone()).collect()
    }

    async fn publish_presence(&mut self, now: Instant) -> HttpResult<()> {
        let players = self.room_players(now);
        let fingerprint = serde_json::to_string(&players).expect("json");
        if self.store.get_presence_fingerprint().as_deref() == Some(fingerprint.as_str()) {
            metrics::counter!("coordinator_publish_total", "kind" => "presence", "suppressed" => "true")
                .increment(1);
            return Ok(());
        }
        metrics::counter!("coordinator_publish_total", "kind" => "presence", "suppressed" => "false")
            .increment(1);
        self.store.set_presence_fingerprint(&fingerprint);
        self.effects.mirror_presence(&self.world_id, &players).await;
        let members = self.memberships(now).await?;
        self.effects
            .publish(
                RealtimeEvent {
                    world_id: self.world_id.clone(),
                    kind: RealtimeEventKind::PresenceChanged,
                    runtime: None,
                    room_players: Some(players),
                },
                Some(Self::active_recipients(&members)),
            )
            .await;
        Ok(())
    }

    /// Runs after every externally visible operation: mirror, push
    /// runtime-changed on material change, re-arm the single alarm.
    async fn after_state_change(&mut self, now: Instant) -> HttpResult<()> {
        let runtime = self.store.get_runtime();
        let memberships = self.memberships(now).await?;
        let electable: Vec<RuntimeCandidate> = self
            .electable_waiters(now)
            .into_iter()
            .map(|w| RuntimeCandidate { player_uuid: w.player_uuid, player_name: w.player_name })
            .collect();
        let candidate = choose_preferred_candidate(&electable, &memberships);
        let warning = self.store.get_warning();
        let status =
            to_runtime_status(&self.world_id, runtime.as_ref(), candidate.as_ref(), warning.as_ref());
        let fingerprint = status_fingerprint(&status);
        let changed = self.store.get_status_fingerprint().as_deref() != Some(fingerprint.as_str());
        metrics::counter!("coordinator_publish_total", "kind" => "runtime", "suppressed" => if changed { "false" } else { "true" })
            .increment(1);
        if changed {
            self.store.set_status_fingerprint(&fingerprint);
            self.effects.mirror_runtime(&self.world_id, &status).await;
            self.effects
                .publish(
                    RealtimeEvent {
                        world_id: self.world_id.clone(),
                        kind: RealtimeEventKind::RuntimeChanged,
                        runtime: Some(status),
                        room_players: None,
                    },
                    Some(Self::active_recipients(&memberships)),
                )
                .await;
        }
        let next = self.next_deadline(now);
        self.effects.schedule_alarm(&self.world_id, next).await;
        Ok(())
    }

    pub fn next_deadline(&self, now: Instant) -> Option<Instant> {
        let mut candidates: Vec<i64> = Vec::new();
        if let Some(runtime) = self.store.get_runtime() {
            if let Some(d) = phase_deadline(&runtime) {
                candidates.push(ms(d));
            }
            if let Some(g) = self.store.get_host_link().grace_deadline_at.as_deref().and_then(parse) {
                candidates.push(ms(g));
            }
        }
        for w in self.store.list_waiters() {
            if let Some(t) = parse(&w.updated_at) {
                candidates.push(ms(t) + HANDOFF_WAITER_TIMEOUT_MS);
                let stale_at = ms(t) + WAITER_ELECTION_FRESHNESS_MS;
                if stale_at > ms(now) {
                    candidates.push(stale_at);
                }
            }
        }
        for e in self.store.list_legacy_presence() {
            // Only present, unexpired entries arm an alarm (tombstones/expired
            // entries used to re-arm a 1-second alarm loop).
            if e.present {
                if let Some(t) = parse(&e.expires_at) {
                    if t > now {
                        candidates.push(ms(t));
                    }
                }
            }
        }
        for e in self.store.list_socket_presence() {
            if let Some(g) = e.grace_deadline_at.as_deref().and_then(parse) {
                if g > now {
                    candidates.push(ms(g));
                }
            }
        }
        let min = candidates.into_iter().min()?;
        Some(time::from_millis(min.max(ms(now) + 1_000)))
    }
}
