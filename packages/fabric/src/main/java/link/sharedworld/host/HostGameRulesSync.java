package link.sharedworld.host;

import link.sharedworld.SharedWorldDevSessionBridge;
import link.sharedworld.api.SharedWorldApiClient;
import link.sharedworld.api.SharedWorldModels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * Two-way sync between the live server's managed gamerules and the backend's
 * world settings during a host session: owner-chosen settings arriving on the
 * heartbeat are applied to the server, and in-game /gamerule (or difficulty /
 * default game mode) changes are detected against a recorded baseline and
 * reported back so they survive the session. The hosting manager owns the
 * polling cadence and attempt authority; this class owns the baseline, the
 * applied-revision echo kill, and the single-push-in-flight guard.
 */
final class HostGameRulesSync {
    private static final Logger LOGGER = LoggerFactory.getLogger("sharedworld");

    private final SharedWorldApiClient apiClient;
    private final HostingEvents events;
    private final Executor backgroundExecutor;
    private final Executor mainThreadExecutor;

    /** Last settings revision pushed to the live server; -1 = none this session. */
    private volatile long appliedSettingsRevision = -1;
    /**
     * Baseline snapshot of the live server's managed gamerules (main-thread
     * mutated); null = unknown. The first snapshot after any (re)baseline only
     * records values, so owner-applied settings are never echoed back as a
     * host report; later snapshots that differ are pushed to the backend.
     */
    private volatile WorldSettingsReader.Snapshot lastConfirmedGameRules;
    private final AtomicLong pushInFlight = new AtomicLong();

    /**
     * The slice of host-attempt authority a gamerules push needs: identity for
     * the report call, the attempt generation keying the in-flight guard, and
     * live validity checks evaluated on the main thread at decision time.
     */
    record Authority(
            String worldId,
            long runtimeEpoch,
            String hostToken,
            long generation,
            BooleanSupplier stillCurrentAttempt,
            BooleanSupplier acceptingLiveChanges
    ) {
    }

    HostGameRulesSync(SharedWorldApiClient apiClient, HostingEvents events, Executor backgroundExecutor, Executor mainThreadExecutor) {
        this.apiClient = apiClient;
        this.events = events;
        this.backgroundExecutor = backgroundExecutor;
        this.mainThreadExecutor = mainThreadExecutor;
    }

    /** Full reset for a fresh or torn-down host session. */
    void reset() {
        this.pushInFlight.set(0L);
        this.lastConfirmedGameRules = null;
        this.appliedSettingsRevision = -1;
    }

    /** The attempt generation changed; any in-flight push is now stale. */
    void abandonInFlightPush() {
        this.pushInFlight.set(0L);
    }

    /**
     * Something rewrote gamerules on the server outside the snapshot flow
     * (e.g. the owner's local settings change), so the recorded baseline is
     * stale: the next snapshot re-baselines instead of reporting those values
     * back as an in-game change.
     */
    void invalidateBaseline() {
        this.lastConfirmedGameRules = null;
    }

    /** The session just went live: settings re-apply and the baseline restarts. */
    void rebaselineForNewLiveSession() {
        this.appliedSettingsRevision = -1;
        this.lastConfirmedGameRules = null;
    }

    /**
     * Apply owner-chosen world settings carried by the heartbeat. The revision
     * starts unapplied on every host session, so the first live heartbeat
     * configures the freshly booted server and later bumps reach it within one
     * heartbeat interval.
     */
    void applyHeartbeatSettings(SharedWorldModels.WorldSettingsDto settings, Long settingsRevision) {
        if (settings == null || settingsRevision == null || !SharedWorldDevSessionBridge.isHostingSharedWorld()) {
            return;
        }
        if (settingsRevision == this.appliedSettingsRevision) {
            return;
        }
        this.appliedSettingsRevision = settingsRevision;
        // Applying settings rewrites gamerules on the server, so the recorded
        // baseline is stale: the next snapshot re-baselines instead of
        // reporting the owner's own values back as an in-game change.
        this.lastConfirmedGameRules = null;
        this.events.onWorldSettingsChanged(settings);
    }

    /**
     * Snapshot the live server's managed rules and, when they differ from the
     * recorded baseline, push them to the backend so in-game /gamerule changes
     * survive the session. The caller owns the polling cadence.
     */
    void maybeRequestSnapshot(Authority authority) {
        if (!authority.acceptingLiveChanges().getAsBoolean()) {
            return;
        }
        this.events.onWorldGameRulesSnapshotRequested(snapshot ->
                this.mainThreadExecutor.execute(() -> handleSnapshot(authority, snapshot)));
    }

    /** Package-private for tests; production entry is {@link #maybeRequestSnapshot}. */
    void handleSnapshot(Authority authority, WorldSettingsReader.Snapshot snapshot) {
        if (snapshot == null
                || snapshot.gamerules().isEmpty()
                || !authority.acceptingLiveChanges().getAsBoolean()
                || !SharedWorldDevSessionBridge.isHostingSharedWorld()) {
            return;
        }
        WorldSettingsReader.Snapshot baseline = this.lastConfirmedGameRules;
        if (baseline == null) {
            this.lastConfirmedGameRules = snapshot;
            return;
        }
        if (baseline.equals(snapshot)) {
            return;
        }
        if (!this.pushInFlight.compareAndSet(0L, authority.generation())) {
            // A push is already in flight; the next poll re-diffs and retries.
            return;
        }
        push(authority, snapshot);
    }

    /**
     * One last change check before the server goes down, so a /gamerule typed
     * moments before quitting still persists (the backend accepts the report
     * during host-finalizing). Best-effort: a failure is only a debug log and
     * at most one heartbeat interval of changes is lost on a hard crash.
     */
    void flushBeforeRelease(Authority authority) {
        if (this.lastConfirmedGameRules == null) {
            return;
        }
        this.events.onWorldGameRulesSnapshotRequested(snapshot -> this.mainThreadExecutor.execute(() -> {
            WorldSettingsReader.Snapshot baseline = this.lastConfirmedGameRules;
            if (snapshot == null
                    || snapshot.gamerules().isEmpty()
                    || !authority.stillCurrentAttempt().getAsBoolean()
                    || baseline == null
                    || baseline.equals(snapshot)) {
                return;
            }
            if (this.pushInFlight.compareAndSet(0L, authority.generation())) {
                push(authority, snapshot);
            }
        }));
    }

    private void push(Authority authority, WorldSettingsReader.Snapshot snapshot) {
        CompletableFuture.runAsync(() -> {
            try {
                SharedWorldModels.HostGameRulesReportResponseDto response = this.apiClient.reportHostGameRules(
                        authority.worldId(),
                        authority.runtimeEpoch(),
                        authority.hostToken(),
                        snapshot.gamerules(),
                        snapshot.difficulty(),
                        snapshot.defaultGameMode()
                );
                this.mainThreadExecutor.execute(() -> onPushSucceeded(authority, snapshot, response));
            } catch (Exception exception) {
                LOGGER.debug("SharedWorld gamerule report failed; retrying on the next poll", exception);
            }
        }, this.backgroundExecutor).whenComplete((unused, error) ->
                this.mainThreadExecutor.execute(() -> clearPushInFlight(authority)));
    }

    /** Package-private for tests; production entry is the push completion. */
    void onPushSucceeded(
            Authority authority,
            WorldSettingsReader.Snapshot snapshot,
            SharedWorldModels.HostGameRulesReportResponseDto response
    ) {
        if (!authority.stillCurrentAttempt().getAsBoolean() || response == null || response.settingsRevision() == null) {
            return;
        }
        this.lastConfirmedGameRules = snapshot;
        // Echo kill: record the merged revision as applied WITHOUT re-applying
        // gamerules (the server already holds them; a heartbeat racing a second
        // in-game change must not clobber it). Difficulty/game mode from the
        // merged settings still apply so an owner save at a skipped revision
        // is not lost.
        this.appliedSettingsRevision = response.settingsRevision();
        SharedWorldModels.WorldSettingsDto merged = response.settings();
        if (merged != null && (merged.difficulty() != null || merged.defaultGameMode() != null)) {
            this.events.onWorldSettingsChanged(
                    new SharedWorldModels.WorldSettingsDto(merged.difficulty(), merged.defaultGameMode(), null));
        }
    }

    private void clearPushInFlight(Authority authority) {
        this.pushInFlight.compareAndSet(authority.generation(), 0L);
    }
}
