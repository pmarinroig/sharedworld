package link.sharedworld.host;

import link.sharedworld.SharedWorldText;
import link.sharedworld.api.SharedWorldModels.WorldRuntimeStatusDto;

final class ReleaseTerminalStateSupport {
    private ReleaseTerminalStateSupport() {
    }

    static boolean isRuntimeStillOwnedByHost(String currentPlayerUuid, long expectedRuntimeEpoch, WorldRuntimeStatusDto runtime) {
        if (runtime == null) {
            return false;
        }
        if (runtime.runtimeEpoch() != expectedRuntimeEpoch) {
            return false;
        }
        if (runtime.hostUuid() == null || runtime.hostUuid().isBlank()) {
            return false;
        }
        return runtime.hostUuid().equalsIgnoreCase(currentPlayerUuid)
                && ("host-starting".equals(runtime.phase())
                || "host-live".equals(runtime.phase())
                || "host-finalizing".equals(runtime.phase()));
    }

    static Resolution resolutionFor(SharedWorldReleaseCoordinator.ForcedExitReason reason, String message) {
        return switch (reason) {
            case WORLD_DELETED -> new Resolution(
                    SharedWorldTerminalReasonKind.TERMINATED_DELETED,
                    message == null || message.isBlank()
                            ? SharedWorldText.string("screen.sharedworld.deleted_detail")
                            : message
            );
            case MEMBERSHIP_REVOKED -> new Resolution(
                    SharedWorldTerminalReasonKind.TERMINATED_REVOKED,
                    message == null || message.isBlank()
                            ? SharedWorldText.string("screen.sharedworld.revoked_detail")
                            : message
            );
            case OBSOLETE_LOCAL_STATE -> new Resolution(
                    SharedWorldTerminalReasonKind.OBSOLETE_LOCAL_STATE,
                    message
            );
            default -> new Resolution(
                    SharedWorldTerminalReasonKind.RECOVERABLE_REMOTE_FAILURE,
                    message
            );
        };
    }

    static SharedWorldReleasePhase phaseFor(SharedWorldTerminalReasonKind reasonKind) {
        return switch (reasonKind) {
            case TERMINATED_DELETED -> SharedWorldReleasePhase.TERMINATED_DELETED;
            case TERMINATED_REVOKED -> SharedWorldReleasePhase.TERMINATED_REVOKED;
            default -> SharedWorldReleasePhase.ERROR_RECOVERABLE;
        };
    }

    /**
     * Must never return null: a release view's errorKind feeds screen routing
     * directly, and a persisted ERROR_RECOVERABLE record restored after a client
     * restart has no in-memory kind to fall back on. Absent specifics, an
     * interrupted release IS a recoverable remote failure.
     */
    static SharedWorldTerminalReasonKind reasonKindForPhase(SharedWorldReleasePhase phase) {
        return switch (phase) {
            case TERMINATED_DELETED -> SharedWorldTerminalReasonKind.TERMINATED_DELETED;
            case TERMINATED_REVOKED -> SharedWorldTerminalReasonKind.TERMINATED_REVOKED;
            default -> SharedWorldTerminalReasonKind.RECOVERABLE_REMOTE_FAILURE;
        };
    }

    record Resolution(SharedWorldTerminalReasonKind reasonKind, String message) {
    }
}
