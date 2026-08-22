//! Account-scoped operations: Google Drive unlink and full account deletion.
//! Nothing here existed in the TS worker — the lane-D forwarder passes these
//! routes through to the box untouched.

use sw_contracts::AccountDeleteStepResponse;
use sw_db::repo::UserRecord;

use crate::http_error::{HttpError, HttpResult};
use crate::request::RequestContext;
use crate::service::{snapshots, worlds, ServiceContext};
use crate::storage::StorageBinding;
use crate::time::{self, Instant};

/// Undashed like every stored player UUID. FK columns that must survive a
/// player's deletion (backup creators in other players' worlds) re-point here.
pub const SENTINEL_PLAYER_UUID: &str = "00000000000000000000000000000000";
pub const SENTINEL_PLAYER_NAME: &str = "Deleted Player";

/// Provider file deletions per `DELETE /account` call; the client loops.
const DRIVE_SWEEP_DELETE_BUDGET: usize = 25;
/// Wall-clock cap on one call's sweep: sequential Drive deletes can take a
/// second each, and the response must beat the client's request timeout.
const DRIVE_SWEEP_BUDGET_MS: u64 = 8_000;

/// `DELETE /storage/account`: removes every storage account the player owns
/// (orphan rows from linking a second Google account included), revokes the
/// app's OAuth grant, and wipes the account-scoped index rows.
///
/// Blocked while any active world is still bound to one of those accounts —
/// worlds bind to the account row's id, so deleting the row would orphan
/// their storage permanently (re-linking mints a fresh row id).
pub async fn unlink_storage_account(
    svc: &ServiceContext,
    ctx: &RequestContext,
    now: Instant,
) -> HttpResult<()> {
    let provider = svc.storage_provider.provider();
    let bound =
        svc.repository.count_active_worlds_bound_to_player_accounts(provider, &ctx.player_uuid).await?;
    if bound > 0 {
        return Err(HttpError::new(
            409,
            "storage_unlink_blocked",
            "Delete the shared worlds stored on this Google Drive before unlinking it.",
        ));
    }
    let accounts = svc.repository.find_storage_accounts_by_owner(provider, &ctx.player_uuid).await?;
    for account in &accounts {
        let binding = StorageBinding { provider, storage_account_id: Some(account.id.clone()) };
        // Bounded, best-effort drain of queued blob deletes: once the account
        // row is gone, GC can never reach these Drive files again.
        snapshots::sweep_pending_blob_deletes(svc, &binding, now).await?;
        if let Some(cleanup) = svc.storage_provider.account_cleanup() {
            if let Err(error) = cleanup.revoke_account_access(&binding).await {
                tracing::warn!(account = %account.id, cause = %error, "storage token revoke failed");
            }
        }
        svc.repository.delete_storage_objects_for_account(provider, &account.id).await?;
        svc.repository.delete_pending_blob_deletes_for_account(provider, &account.id).await?;
    }
    svc.repository.delete_storage_accounts_for_owner(provider, &ctx.player_uuid).await?;
    svc.repository.delete_storage_link_sessions_for_player(&ctx.player_uuid).await?;
    Ok(())
}

/// What one `DELETE /account` call did; the HTTP layer must drop
/// `invalidated_tokens` from its in-process session cache.
#[derive(Debug)]
pub struct AccountDeleteOutcome {
    pub response: AccountDeleteStepResponse,
    pub invalidated_tokens: Vec<String>,
}

/// One bounded, resumable step of full account deletion. Phase is derived
/// from DB state, so a retried or interrupted deletion just continues.
///
/// Ordering is load-bearing: everything that needs the player's auth or the
/// Drive tokens (sweep, revoke) happens while sessions and storage rows still
/// exist; sessions and the `users` row go last, in the final call.
pub async fn delete_account_step(
    svc: &ServiceContext,
    ctx: &RequestContext,
    now: Instant,
) -> HttpResult<AccountDeleteOutcome> {
    let provider = svc.storage_provider.provider();
    let accounts = svc.repository.find_storage_accounts_by_owner(provider, &ctx.player_uuid).await?;
    if !accounts.is_empty() {
        let bound =
            svc.repository.count_active_worlds_bound_to_player_accounts(provider, &ctx.player_uuid).await?;
        if bound > 0 {
            return Err(HttpError::new(
                409,
                "account_delete_blocked",
                "Delete your shared worlds before deleting your account.",
            ));
        }
        // Drive sweep: delete everything the app holds for these accounts —
        // including files whose index rows were lost — budgeted per call by
        // count AND wall clock: real Drive deletes run sequentially and a
        // step must answer well inside the client's request timeout.
        if let Some(cleanup) = svc.storage_provider.account_cleanup() {
            let started = std::time::Instant::now();
            let out_of_budget = |deleted: usize| {
                deleted >= DRIVE_SWEEP_DELETE_BUDGET
                    || started.elapsed() >= std::time::Duration::from_millis(DRIVE_SWEEP_BUDGET_MS)
            };
            let mut deleted = 0usize;
            'accounts: for account in &accounts {
                let binding = StorageBinding { provider, storage_account_id: Some(account.id.clone()) };
                loop {
                    // A dead grant (revoked at Google, refresh token gone)
                    // must not trap the user in an undeletable account: skip
                    // the sweep for it — they can clear the leftover app data
                    // from Drive's own Manage Apps settings.
                    let (ids, next_page) = match cleanup.list_account_object_ids(&binding, None).await {
                        Ok(page) => page,
                        Err(e) if e.code == "drive_reauth_required" => {
                            tracing::warn!(account = %account.id, "account deletion skips the Drive sweep: authorization is dead");
                            continue 'accounts;
                        }
                        Err(e) => return Err(e),
                    };
                    if ids.is_empty() {
                        break;
                    }
                    let mut page_deleted = 0usize;
                    for file_id in &ids {
                        if out_of_budget(deleted) {
                            break;
                        }
                        cleanup.delete_account_object(&binding, file_id).await?;
                        deleted += 1;
                        page_deleted += 1;
                    }
                    if page_deleted < ids.len() {
                        let remaining =
                            (ids.len() - page_deleted) as i64 + if next_page.is_some() { 100 } else { 0 };
                        return Ok(AccountDeleteOutcome {
                            response: AccountDeleteStepResponse {
                                done: false,
                                phase: "drive_sweep".into(),
                                remaining,
                            },
                            invalidated_tokens: Vec::new(),
                        });
                    }
                }
            }
        }
        // Drive is empty: revoke and drop the storage rows (shares the unlink
        // path, guard included — it re-checks against racing world creation).
        unlink_storage_account(svc, ctx, now).await?;
    }

    // Finalize. Leave (or tear down) any world the player is still a member
    // of — the client deletes owned worlds beforehand for progress fidelity,
    // this is the backstop that also covers guest memberships.
    for world in svc.repository.list_worlds_for_player(&ctx.player_uuid).await? {
        worlds::delete_world(svc, ctx, &world.id, now).await?;
    }
    svc.repository
        .upsert_user(UserRecord {
            player_uuid: SENTINEL_PLAYER_UUID.into(),
            player_name: SENTINEL_PLAYER_NAME.into(),
            created_at: time::to_iso(now),
        })
        .await?;
    for world_id in svc.repository.list_world_ids_for_owner(&ctx.player_uuid).await? {
        svc.repository.hard_delete_world(&world_id).await?;
    }
    svc.repository.scrub_player_references(&ctx.player_uuid, SENTINEL_PLAYER_UUID).await?;
    svc.repository.delete_storage_link_sessions_for_player(&ctx.player_uuid).await?;
    let tokens = svc.repository.list_session_tokens_for_player(&ctx.player_uuid).await?;
    svc.repository.delete_sessions_for_player(&ctx.player_uuid).await?;
    svc.repository.delete_user(&ctx.player_uuid).await?;
    // A uuid prefix only: the full identifier outliving the deleted account
    // in month-retained journals would undercut "delete all of my data".
    tracing::info!(player_uuid_prefix = %&ctx.player_uuid[..ctx.player_uuid.len().min(8)], "SharedWorld account deleted");
    Ok(AccountDeleteOutcome {
        response: AccountDeleteStepResponse { done: true, phase: "finalizing".into(), remaining: 0 },
        invalidated_tokens: tokens,
    })
}
