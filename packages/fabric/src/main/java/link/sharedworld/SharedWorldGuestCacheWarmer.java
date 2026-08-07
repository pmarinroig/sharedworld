package link.sharedworld;

import link.sharedworld.api.SharedWorldApiClient;
import link.sharedworld.api.SharedWorldModels.SnapshotManifestDto;
import link.sharedworld.sync.ManagedWorldStore;
import link.sharedworld.sync.WorldSyncCoordinator;
import net.minecraft.client.Minecraft;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SharedWorldGuestCacheWarmer implements link.sharedworld.realtime.RealtimeEvents.Subscriber {
    private static final long POLL_INTERVAL_MS = 30_000L;
    /** Safety-net cadence while snapshot-changed pushes drive warmups (0.3.0). */
    private static final long PUSH_CONNECTED_POLL_INTERVAL_MS = 5 * 60_000L;

    private final SharedWorldApiClient apiClient;
    private final WorldSyncCoordinator syncCoordinator;
    private final HostPlayerIdentity hostPlayerIdentity;
    private final AtomicBoolean inFlight = new AtomicBoolean();
    private volatile String activeWorldId;
    private volatile String latestSnapshotId;
    private volatile long lastPollAt;
    private volatile String pausedWorldId;
    private volatile boolean pushConnected;
    private volatile boolean pushTriggered;

    @Override
    public void onRealtimeConnectionChanged(boolean connected) {
        this.pushConnected = connected;
    }

    /** A pushed snapshot-changed for the active world warms on the next tick. */
    @Override
    public void onRealtimeEvent(link.sharedworld.api.SharedWorldModels.RealtimeEventDto event) {
        if ("snapshot-changed".equals(event.kind()) && event.worldId().equals(this.activeWorldId)) {
            this.pushTriggered = true;
        }
    }

    public SharedWorldGuestCacheWarmer(SharedWorldApiClient apiClient) {
        this(apiClient, apiClient::authenticatedWorldPlayerUuidWithHyphens);
    }

    public SharedWorldGuestCacheWarmer(SharedWorldApiClient apiClient, HostPlayerIdentity hostPlayerIdentity) {
        this.apiClient = apiClient;
        this.syncCoordinator = new WorldSyncCoordinator(apiClient, new ManagedWorldStore());
        this.hostPlayerIdentity = hostPlayerIdentity;
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
     * The current guest play session plus latest remote manifest metadata.
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

        long now = System.currentTimeMillis();
        boolean worldChanged = !session.worldId().equals(this.activeWorldId);
        if (worldChanged) {
            this.activeWorldId = session.worldId();
            this.latestSnapshotId = null;
            this.lastPollAt = 0L;
        }
        long interval = this.pushConnected ? PUSH_CONNECTED_POLL_INTERVAL_MS : POLL_INTERVAL_MS;
        if (!worldChanged && !this.pushTriggered && now - this.lastPollAt < interval) {
            return;
        }
        this.pushTriggered = false;
        if (!this.inFlight.compareAndSet(false, true)) {
            return;
        }

        this.lastPollAt = now;
        CompletableFuture.runAsync(() -> {
            try {
                SnapshotManifestDto latestManifest = this.apiClient.latestManifest(session.worldId());
                if (latestManifest == null || latestManifest.snapshotId() == null || latestManifest.snapshotId().equals(this.latestSnapshotId)) {
                    return;
                }
                this.syncCoordinator.ensureCanonicalSynchronizedWorkingCopy(
                        session.worldId(),
                        this.hostPlayerIdentity.currentWorldPlayerUuidWithHyphens()
                );
                this.latestSnapshotId = latestManifest.snapshotId();
            } catch (Exception exception) {
                SharedWorldClient.LOGGER.debug("SharedWorld guest cache warmup failed", exception);
            } finally {
                this.inFlight.set(false);
            }
        }, SharedWorldClient.ioExecutor());
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
            this.lastPollAt = 0L;
        }
        this.inFlight.set(false);
    }
}
