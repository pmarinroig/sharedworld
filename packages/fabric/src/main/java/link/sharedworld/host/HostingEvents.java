package link.sharedworld.host;

/**
 * Side effects the hosting manager raises into the rest of the client.
 *
 * The hosting manager owns only local host execution; everything that concerns
 * other components (guest cache warming, play session tracking, the release
 * coordinator's forced-exit flows) goes through this seam. Production wiring
 * lives in SharedWorldClient; tests install {@link #NONE} or a recording
 * implementation, so host execution logic never reaches into client statics.
 */
public interface HostingEvents {

    /** A new local host attempt now owns this world. */
    default void onHostStartupBegan(String worldId) {
    }

    /** The backend confirmed this client as the live host for the world. */
    default void onHostSessionLive(String worldId, String worldName) {
    }

    /** Local host execution state was cleared (release, terminal exit, or cancel). */
    default void onHostStateCleared(String worldId) {
    }

    /** The backend reported the hosted world as deleted. */
    default void onWorldDeleted() {
    }

    /** The backend revoked this player's membership while hosting. */
    default void onMembershipRevoked() {
    }

    /** Backend authority for the current host attempt was lost; the forced-exit owner takes over. */
    default void onHostAuthorityLost(
            SharedWorldHostingManager.ActiveHostSession session,
            SharedWorldReleaseCoordinator.HostAuthorityLossStage stage,
            String message
    ) {
    }

    /** Whether a persisted graceful-release recovery exists for this world. */
    default boolean hasPendingReleaseRecovery(String worldId) {
        return false;
    }

    HostingEvents NONE = new HostingEvents() {
    };
}
