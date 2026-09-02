package link.sharedworld.host;

import link.sharedworld.progress.SharedWorldProgressState;
import net.minecraft.network.chat.Component;

/** Progress states for the hosting manager; every phase key has exactly one label translation. */
final class HostProgressStateFactory {
    private static final String STARTING_TITLE = "screen.sharedworld.starting_title";
    private static final String SAVING_TITLE = "screen.sharedworld.saving_title";

    private HostProgressStateFactory() {
    }

    static SharedWorldProgressState startupIndeterminate(String phase, SharedWorldProgressState previousState) {
        return indeterminate(STARTING_TITLE, phase, previousState);
    }

    static SharedWorldProgressState startupDeterminate(String phase, double targetFraction, SharedWorldProgressState previousState, Long bytesDone, Long bytesTotal) {
        return determinate(STARTING_TITLE, phase, targetFraction, previousState, bytesDone, bytesTotal);
    }

    static SharedWorldProgressState savingIndeterminate(String phase, SharedWorldProgressState previousState) {
        return indeterminate(SAVING_TITLE, phase, previousState);
    }

    static SharedWorldProgressState savingDeterminate(String phase, double targetFraction, SharedWorldProgressState previousState, Long bytesDone, Long bytesTotal) {
        return determinate(SAVING_TITLE, phase, targetFraction, previousState, bytesDone, bytesTotal);
    }

    static SharedWorldProgressState releasingState(boolean backendFinalizationStarted, SharedWorldProgressState previousState) {
        return savingIndeterminate(backendFinalizationStarted ? "finishing_up" : "leaving_world", previousState);
    }

    private static SharedWorldProgressState indeterminate(String titleKey, String phase, SharedWorldProgressState previousState) {
        return SharedWorldProgressState.indeterminate(Component.translatable(titleKey), label(phase), phase, previousState);
    }

    private static SharedWorldProgressState determinate(String titleKey, String phase, double targetFraction, SharedWorldProgressState previousState, Long bytesDone, Long bytesTotal) {
        return SharedWorldProgressState.determinate(Component.translatable(titleKey), label(phase), phase, targetFraction, previousState, bytesDone, bytesTotal);
    }

    private static Component label(String phase) {
        // Spelled out in full so the localization parity test sees every key.
        return Component.translatable(switch (phase) {
            case "preparing_world" -> "screen.sharedworld.progress.preparing_world";
            case "finishing_up" -> "screen.sharedworld.progress.finishing_up";
            case "becoming_host" -> "screen.sharedworld.progress.becoming_host";
            case "connecting" -> "screen.sharedworld.progress.connecting";
            case "saving_world" -> "screen.sharedworld.progress.saving_world";
            case "leaving_world" -> "screen.sharedworld.progress.leaving_world";
            case "syncing_world" -> "screen.sharedworld.progress.syncing_world";
            case "recovering_local_world" -> "screen.sharedworld.progress.recovering_local_world";
            case "uploading_local_changes" -> "screen.sharedworld.progress.uploading_local_changes";
            default -> throw new IllegalArgumentException("Unknown host progress phase: " + phase);
        });
    }
}
