//! Pure reducers over the world runtime record (`runtime-protocol.ts`).

use serde::{Deserialize, Serialize};
use sw_contracts::{
    HostAssignment, HostStartupProgress, MembershipRole, UncleanShutdownPhase, UncleanShutdownWarning,
    WorldRuntimePhase, WorldRuntimeStatus, HOST_LEASE_TIMEOUT_MS, HOST_LIVE_LEASE_TIMEOUT_MS,
};

use crate::time::{self, Instant};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RuntimeCandidate {
    pub player_uuid: String,
    pub player_name: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RuntimeWaiter {
    pub player_uuid: String,
    pub player_name: String,
    pub waiter_session_id: String,
    pub waiting: bool,
    pub updated_at: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RuntimeMembership {
    pub player_uuid: String,
    pub player_name: String,
    pub role: MembershipRole,
    pub joined_at: String,
    pub deleted_at: Option<String>,
}

/// JSON shape is identical to the DO's `runtime` kv value (cutover import).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WorldRuntimeRecord {
    pub world_id: String,
    pub phase: WorldRuntimePhase,
    pub runtime_epoch: i64,
    pub runtime_token: Option<String>,
    pub host_uuid: Option<String>,
    pub host_player_name: Option<String>,
    pub candidate_uuid: Option<String>,
    pub join_target: Option<String>,
    pub claimed_at: Option<String>,
    pub expires_at: Option<String>,
    pub startup_deadline_at: Option<String>,
    pub runtime_token_issued_at: Option<String>,
    pub last_progress_at: Option<String>,
    pub updated_at: String,
    pub revoked_at: Option<String>,
    pub startup_progress: Option<HostStartupProgress>,
    pub host_minecraft_version: Option<String>,
    /// Box-only (cutover): accept any token for this epoch's host until the
    /// runtime retires. Absent in DO dumps.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub token_amnesty: Option<bool>,
}

pub fn choose_preferred_candidate(
    waiters: &[RuntimeCandidate],
    memberships: &[RuntimeMembership],
) -> Option<RuntimeCandidate> {
    struct C<'a> {
        uuid: &'a str,
        name: &'a str,
        owner: bool,
        joined_at: &'a str,
    }
    let mut candidates: Vec<C> = waiters
        .iter()
        .filter_map(|w| {
            let m = memberships.iter().find(|m| m.player_uuid == w.player_uuid)?;
            if m.deleted_at.is_some() {
                return None;
            }
            Some(C {
                uuid: &w.player_uuid,
                name: &m.player_name,
                owner: m.role == MembershipRole::Owner,
                joined_at: &m.joined_at,
            })
        })
        .collect();
    if candidates.is_empty() {
        return None;
    }
    candidates.sort_by(|l, r| {
        r.owner.cmp(&l.owner).then_with(|| l.joined_at.cmp(r.joined_at)).then_with(|| l.uuid.cmp(r.uuid))
    });
    Some(RuntimeCandidate {
        player_uuid: candidates[0].uuid.to_string(),
        player_name: candidates[0].name.to_string(),
    })
}

fn parse(s: &str) -> Option<Instant> {
    time::parse_iso(s)
}

pub fn phase_deadline(runtime: &WorldRuntimeRecord) -> Option<Instant> {
    match runtime.phase {
        WorldRuntimePhase::HostFinalizing => {
            let last = runtime.last_progress_at.as_deref().unwrap_or(&runtime.updated_at);
            if last.is_empty() {
                return None;
            }
            parse(last).map(|t| t + chrono::Duration::milliseconds(HOST_LEASE_TIMEOUT_MS))
        }
        WorldRuntimePhase::HostStarting => runtime.startup_deadline_at.as_deref().and_then(parse),
        WorldRuntimePhase::HostLive => runtime.expires_at.as_deref().and_then(parse),
        _ => None,
    }
}

/// Expired host-starting/host-live runtimes are dropped; finalizing past its
/// stall deadline too (same rule: any phase deadline in the past).
pub fn resolve_runtime_timeout(
    runtime: Option<&WorldRuntimeRecord>,
    now: Instant,
) -> Option<WorldRuntimeRecord> {
    let runtime = runtime?;
    match phase_deadline(runtime) {
        Some(d) if d <= now => None,
        _ => Some(runtime.clone()),
    }
}

pub fn timed_out_unclean_shutdown_warning(
    runtime: Option<&WorldRuntimeRecord>,
    now: Instant,
) -> Option<UncleanShutdownWarning> {
    let runtime = runtime?;
    let phase = match runtime.phase {
        WorldRuntimePhase::HostLive => UncleanShutdownPhase::HostLive,
        WorldRuntimePhase::HostFinalizing => UncleanShutdownPhase::HostFinalizing,
        _ => return None,
    };
    match phase_deadline(runtime) {
        Some(d) if d <= now => {}
        _ => return None,
    }
    Some(UncleanShutdownWarning {
        host_uuid: runtime.host_uuid.clone()?,
        host_player_name: runtime.host_player_name.clone()?,
        phase,
        runtime_epoch: runtime.runtime_epoch,
        recorded_at: time::to_iso(now),
    })
}

pub struct Assigned {
    pub runtime: WorldRuntimeRecord,
    pub assignment: HostAssignment,
}

/// Every ownership change increments runtimeEpoch and mints a fresh token.
pub fn assign_host_starting(
    world_id: &str,
    assignee: &RuntimeCandidate,
    previous_epoch: Option<i64>,
    now: Instant,
    token: String,
) -> Assigned {
    let runtime_epoch = previous_epoch.unwrap_or(0) + 1;
    let issued_at = time::to_iso(now);
    let startup_deadline_at = time::plus_ms_iso(now, HOST_LEASE_TIMEOUT_MS);
    let runtime = WorldRuntimeRecord {
        world_id: world_id.to_string(),
        phase: WorldRuntimePhase::HostStarting,
        runtime_epoch,
        runtime_token: Some(token.clone()),
        host_uuid: Some(assignee.player_uuid.clone()),
        host_player_name: Some(assignee.player_name.clone()),
        candidate_uuid: Some(assignee.player_uuid.clone()),
        join_target: None,
        claimed_at: Some(issued_at.clone()),
        expires_at: Some(startup_deadline_at.clone()),
        startup_deadline_at: Some(startup_deadline_at.clone()),
        runtime_token_issued_at: Some(issued_at.clone()),
        last_progress_at: None,
        updated_at: issued_at,
        revoked_at: None,
        startup_progress: None,
        host_minecraft_version: None,
        token_amnesty: None,
    };
    Assigned {
        runtime,
        assignment: HostAssignment {
            world_id: world_id.to_string(),
            player_uuid: assignee.player_uuid.clone(),
            player_name: assignee.player_name.clone(),
            runtime_epoch,
            host_token: token,
            startup_deadline_at: Some(startup_deadline_at),
        },
    }
}

pub fn move_to_live(
    runtime: &WorldRuntimeRecord,
    join_target: Option<&str>,
    now: Instant,
) -> WorldRuntimeRecord {
    WorldRuntimeRecord {
        phase: WorldRuntimePhase::HostLive,
        join_target: join_target.map(|s| s.to_string()).or_else(|| runtime.join_target.clone()),
        expires_at: Some(time::plus_ms_iso(now, HOST_LIVE_LEASE_TIMEOUT_MS)),
        startup_deadline_at: None,
        updated_at: time::to_iso(now),
        ..runtime.clone()
    }
}

/// host-starting extends its deadline; a non-blank join target promotes to live.
pub fn refresh_live_runtime(
    runtime: &WorldRuntimeRecord,
    join_target: Option<&str>,
    now: Instant,
) -> WorldRuntimeRecord {
    if runtime.phase == WorldRuntimePhase::HostStarting {
        if let Some(jt) = join_target {
            if !jt.trim().is_empty() {
                return move_to_live(runtime, Some(jt), now);
            }
        }
        let extended = time::plus_ms_iso(now, HOST_LEASE_TIMEOUT_MS);
        return WorldRuntimeRecord {
            join_target: join_target.map(|s| s.to_string()).or_else(|| runtime.join_target.clone()),
            expires_at: Some(extended.clone()),
            startup_deadline_at: Some(extended),
            updated_at: time::to_iso(now),
            ..runtime.clone()
        };
    }
    WorldRuntimeRecord {
        join_target: join_target.map(|s| s.to_string()).or_else(|| runtime.join_target.clone()),
        expires_at: Some(time::plus_ms_iso(now, HOST_LIVE_LEASE_TIMEOUT_MS)),
        updated_at: time::to_iso(now),
        ..runtime.clone()
    }
}

pub fn move_to_finalizing(runtime: &WorldRuntimeRecord, now: Instant) -> WorldRuntimeRecord {
    let started = time::to_iso(now);
    WorldRuntimeRecord {
        phase: WorldRuntimePhase::HostFinalizing,
        join_target: None,
        expires_at: None,
        startup_deadline_at: None,
        updated_at: started.clone(),
        last_progress_at: Some(started),
        startup_progress: None,
        ..runtime.clone()
    }
}

pub fn set_host_progress(
    runtime: &WorldRuntimeRecord,
    progress: Option<HostStartupProgress>,
    now: Instant,
) -> WorldRuntimeRecord {
    WorldRuntimeRecord {
        last_progress_at: progress.as_ref().map(|p| p.updated_at.clone()),
        startup_progress: progress,
        updated_at: time::to_iso(now),
        ..runtime.clone()
    }
}

/// Exact host epoch/token match; old epochs or tokens fail closed even for
/// the same player. The box-only cutover amnesty accepts any token for the
/// imported host/epoch pair.
pub fn matches_host_authorization(
    runtime: Option<&WorldRuntimeRecord>,
    player_uuid: &str,
    runtime_epoch: Option<i64>,
    host_token: Option<&str>,
) -> bool {
    let Some(r) = runtime else { return false };
    if r.host_uuid.as_deref() != Some(player_uuid) || Some(r.runtime_epoch) != runtime_epoch {
        return false;
    }
    if r.token_amnesty == Some(true) {
        return host_token.is_some();
    }
    match (&r.runtime_token, host_token) {
        (Some(t), Some(h)) => t == h,
        _ => false,
    }
}

pub fn to_runtime_status(
    world_id: &str,
    runtime: Option<&WorldRuntimeRecord>,
    candidate: Option<&RuntimeCandidate>,
    warning: Option<&UncleanShutdownWarning>,
) -> WorldRuntimeStatus {
    let public_warning = warning.cloned();
    match runtime {
        None => WorldRuntimeStatus {
            world_id: world_id.to_string(),
            phase: if candidate.is_some() {
                WorldRuntimePhase::HandoffWaiting
            } else {
                WorldRuntimePhase::Idle
            },
            runtime_epoch: 0,
            host_uuid: None,
            host_player_name: None,
            candidate_uuid: candidate.map(|c| c.player_uuid.clone()),
            candidate_player_name: candidate.map(|c| c.player_name.clone()),
            join_target: None,
            startup_deadline_at: None,
            runtime_token_issued_at: None,
            last_progress_at: None,
            updated_at: None,
            revoked_at: None,
            startup_progress: None,
            unclean_shutdown_warning: public_warning,
            host_minecraft_version: None,
            suggested_poll_interval_ms: None,
        },
        Some(r) => WorldRuntimeStatus {
            world_id: r.world_id.clone(),
            phase: r.phase,
            runtime_epoch: r.runtime_epoch,
            host_uuid: r.host_uuid.clone(),
            host_player_name: r.host_player_name.clone(),
            candidate_uuid: r.candidate_uuid.clone(),
            candidate_player_name: match (&r.candidate_uuid, candidate) {
                (Some(cu), Some(c)) if &c.player_uuid == cu => Some(c.player_name.clone()),
                _ => None,
            },
            join_target: r.join_target.clone(),
            startup_deadline_at: r.startup_deadline_at.clone(),
            runtime_token_issued_at: r.runtime_token_issued_at.clone(),
            last_progress_at: r.last_progress_at.clone(),
            updated_at: Some(r.updated_at.clone()),
            revoked_at: r.revoked_at.clone(),
            startup_progress: r.startup_progress.clone(),
            unclean_shutdown_warning: public_warning,
            host_minecraft_version: r.host_minecraft_version.clone(),
            suggested_poll_interval_ms: None,
        },
    }
}

// ------------------------------------------------------------------------
// `runtime-service-support.ts`
// ------------------------------------------------------------------------

#[derive(Debug, Clone, Default)]
pub struct ResolvedRuntimeState {
    pub runtime: Option<WorldRuntimeRecord>,
    pub candidate: Option<RuntimeCandidate>,
    pub warning: Option<UncleanShutdownWarning>,
    pub retired_runtime_epoch: Option<i64>,
}

pub fn runtime_allows_direct_connect(resolved: &ResolvedRuntimeState) -> bool {
    match &resolved.runtime {
        Some(r) => {
            r.phase == WorldRuntimePhase::HostLive
                && r.revoked_at.is_none()
                && r.join_target.as_deref().is_some_and(|j| !j.is_empty())
        }
        None => false,
    }
}

pub fn runtime_requires_waiting(resolved: &ResolvedRuntimeState) -> bool {
    resolved.runtime.as_ref().is_some_and(|r| r.phase == WorldRuntimePhase::HostFinalizing)
}

pub fn host_assignment_for_current_runtime(
    resolved: &ResolvedRuntimeState,
    player_uuid: &str,
) -> Option<HostAssignment> {
    let r = resolved.runtime.as_ref()?;
    if r.phase != WorldRuntimePhase::HostStarting || r.host_uuid.as_deref() != Some(player_uuid) {
        return None;
    }
    Some(HostAssignment {
        world_id: r.world_id.clone(),
        player_uuid: player_uuid.to_string(),
        player_name: r.host_player_name.clone().unwrap_or_default(),
        runtime_epoch: r.runtime_epoch,
        host_token: r.runtime_token.clone().unwrap_or_default(),
        startup_deadline_at: r.startup_deadline_at.clone(),
    })
}
