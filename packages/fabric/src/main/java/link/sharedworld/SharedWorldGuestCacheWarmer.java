package link.sharedworld;

import link.sharedworld.api.SharedWorldApiClient;
import link.sharedworld.sync.ManagedWorldStore;
import link.sharedworld.sync.WorldSyncCoordinator;
import net.minecraft.client.Minecraft;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Socket-native cache warmer: no periodic polling at all. A pushed
 * snapshot-changed for the active world triggers ONE targeted getWorld fetch
 * (which rides the conditional-GET cache); in the disconnected fallback lane
 * the snapshot id arrives on the presence manager's merged beat instead.
 * Either way, a changed id runs one best-effort warmup.
 */
public final class SharedWorldGuestCacheWarmer implements link.sharedworld.realtime.RealtimeEvents.Subscriber {
    private final SharedWorldApiClient apiClient;
    private final WorldSyncCoordinator syncCoordinator;
    private final HostPlayerIdentity hostPlayerIdentity;
    private final AtomicBoolean inFlight = new AtomicBoolean();
    private volatile String activeWorldId;
    private volatile String latestSnapshotId;
    private volatile String pausedWorldId;
    private volatile boolean fetchTriggered;

    public SharedWorldGuestCacheWarmer(SharedWorldApiClient apiClient) {
        this(apiClient, apiClient::authenticatedWorldPlayerUuidWithHyphens);
    }

    public SharedWorldGuestCacheWarmer(SharedWorldApiClient apiClient, HostPlayerIdentity hostPlayerIdentity) {
        this.apiClient = apiClient;
        this.syncCoordinator = new WorldSyncCoordinator(apiClient, new ManagedWorldStore());
        this.hostPlayerIdentity = hostPlayerIdentity;
    }

    /** A pushed snapshot-changed for the active world fetches on the next tick. */
    @Override
    public void onRealtimeEvent(link.sharedworld.api.SharedWorldModels.RealtimeEventDto event) {
        if ("snapshot-changed".equals(event.kind()) && event.worldId().equals(this.activeWorldId)) {
            this.fetchTriggered = true;
        }
    }

    /**
     * Responsibility:
     * Warm guest-side canonical cache opportunistically without owning any lifecycle transition.
     *
     * Preconditions:
     * The player is already connected as a guest and no host/release flow owns the local client.
     *
     * Postconditions:
     * At most one best-effort cache warmup runs, and failures stay non-fatal.
     *
     * Stale-work rule:
     * Warmup work is abandoned when the active guest world changes or the session ends.
     *
     * Authority source:
     * The current guest play session plus pushed snapshot events / merged-beat snapshot ids.
     */
    public void tick(Minecraft client) {
        SharedWorldPlaySessionTracker.ActiveWorldSession session = SharedWorldClient.playSessionTracker().currentSession();
        if (session == null || session.role() != SharedWorldPlaySessionTracker.SessionRole.GUEST) {
            clearActiveWorld(session == null ? null : session.worldId());
            return;
        }
        if (client.level == null || client.getConnection() == null) {
            return;
        }
        if (SharedWorldClient.hostingManager().phase() != link.sharedworld.host.SharedWorldHostingManager.Phase.IDLE) {
            return;
        }
        if (session.worldId().equals(this.pausedWorldId)) {
            return;
        }

        boolean worldChanged = !session.worldId().equals(this.activeWorldId);
        if (worldChanged) {
            this.activeWorldId = session.worldId();
            this.latestSnapshotId = null;
        }
        if (!this.fetchTriggered) {
            return;
        }
        this.fetchTriggered = false;
        if (!this.inFlight.compareAndSet(false, true)) {
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                // One targeted fetch per snapshot-changed push; getWorld
                // rides the conditional-GET cache so an already-known state
                // costs a 304.
                String remoteSnapshotId = this.apiClient.getWorld(session.worldId()).lastSnapshotId();
                warmIfChanged(session.worldId(), remoteSnapshotId);
            } catch (Exception exception) {
                SharedWorldClient.LOGGER.debug("SharedWorld guest cache warmup failed", exception);
            } finally {
                this.inFlight.set(false);
            }
        }, SharedWorldClient.ioExecutor());
    }

    /**
     * Snapshot id off a merged beat (disconnected fallback lane) — no extra
     * fetch needed, the beat already carried the id. Called on the beat's
     * background executor; gates mirror the tick's.
     */
    public void onMergedSnapshotObservation(String worldId, String remoteSnapshotId) {
        if (worldId == null || !worldId.equals(this.activeWorldId) || worldId.equals(this.pausedWorldId)) {
            return;
        }
        if (SharedWorldClient.hostingManager().phase() != link.sharedworld.host.SharedWorldHostingManager.Phase.IDLE) {
            return;
        }
        if (!this.inFlight.compareAndSet(false, true)) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                warmIfChanged(worldId, remoteSnapshotId);
            } catch (Exception exception) {
                SharedWorldClient.LOGGER.debug("SharedWorld guest cache warmup failed", exception);
            } finally {
                this.inFlight.set(false);
            }
        }, SharedWorldClient.ioExecutor());
    }

    private void warmIfChanged(String worldId, String remoteSnapshotId) throws Exception {
        if (remoteSnapshotId == null || remoteSnapshotId.equals(this.latestSnapshotId)) {
            return;
        }
        this.syncCoordinator.ensureCanonicalSynchronizedWorkingCopy(
                worldId,
                this.hostPlayerIdentity.currentWorldPlayerUuidWithHyphens()
        );
        this.latestSnapshotId = remoteSnapshotId;
    }

    public void onDisconnect(SharedWorldPlaySessionTracker.ActiveWorldSession session) {
        clearActiveWorld(session == null ? null : session.worldId());
    }

    public void pauseWorld(String worldId) {
        this.pausedWorldId = worldId;
    }

    public void resumeWorld(String worldId) {
        if (worldId != null && worldId.equals(this.pausedWorldId)) {
            this.pausedWorldId = null;
        }
    }

    private void clearActiveWorld(String worldId) {
        if (worldId == null || worldId.equals(this.activeWorldId)) {
            this.activeWorldId = null;
            this.latestSnapshotId = null;
            this.fetchTriggered = false;
        }
        this.inFlight.set(false);
    }
}
