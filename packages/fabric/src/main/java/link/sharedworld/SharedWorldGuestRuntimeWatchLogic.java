package link.sharedworld;

import link.sharedworld.api.SharedWorldModels.WorldRuntimeStatusDto;

/**
 * Responsibility:
 * Decide, from one authoritative runtime observation, whether the hosting session a guest is
 * connected to is still alive.
 *
 * Preconditions:
 * The caller is currently connected as a guest and knows the runtime epoch it joined under
 * (0 when unknown).
 *
 * Postconditions:
 * Exactly one outcome is returned; every departure outcome is backed by an authoritative
 * backend state that can never revert to "this epoch is live again".
 *
 * Stale-work rule:
 * Anything ambiguous (missing status, unknown phase) stays CONTINUE — only the vanilla
 * connection remains the backstop there. A departure must never be inferred from a guess.
 *
 * Authority source:
 * The backend runtime status; epochs are monotonic and finalizing/idle states are terminal
 * for the epoch the guest joined under.
 */
public final class SharedWorldGuestRuntimeWatchLogic {
    private SharedWorldGuestRuntimeWatchLogic() {
    }

    public static Outcome evaluate(long joinedRuntimeEpoch, WorldRuntimeStatusDto runtime) {
        if (runtime == null || runtime.phase() == null) {
            return Outcome.CONTINUE;
        }
        boolean epochKnown = joinedRuntimeEpoch > 0L;
        return switch (runtime.phase()) {
            case "host-live" -> epochKnown && runtime.runtimeEpoch() != joinedRuntimeEpoch
                    ? Outcome.HOST_CHANGED
                    : Outcome.CONTINUE;
            case "host-starting" -> epochKnown && runtime.runtimeEpoch() != joinedRuntimeEpoch
                    ? Outcome.HOST_CHANGED
                    : Outcome.CONTINUE;
            case "host-finalizing" -> epochKnown && runtime.runtimeEpoch() != joinedRuntimeEpoch
                    ? Outcome.HOST_CHANGED
                    : Outcome.HOST_LEAVING;
            case "idle", "handoff-waiting" -> Outcome.HOST_GONE;
            default -> Outcome.CONTINUE;
        };
    }

    public enum Outcome {
        /** The hosting session the guest joined is still authoritative. */
        CONTINUE,
        /** The current host froze the runtime to finalize; the session is ending now. */
        HOST_LEAVING,
        /** No runtime exists anymore; the host finished finalizing or timed out. */
        HOST_GONE,
        /** A different runtime epoch owns the world; the joined session is already dead. */
        HOST_CHANGED;

        boolean isDeparture() {
            return this != CONTINUE;
        }
    }
}
