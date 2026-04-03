package link.sharedworld.host;

import link.sharedworld.progress.SharedWorldProgressState;
import net.minecraft.network.chat.Component;

final class HostProgressStateFactory {
    private HostProgressStateFactory() {
    }

    static SharedWorldProgressState startupIndeterminate(String phase, Component label, SharedWorldProgressState previousState) {
        return SharedWorldProgressState.indeterminate(
                Component.translatable("screen.sharedworld.starting_title"),
                label,
                phase,
                previousState
        );
    }

    static SharedWorldProgressState startupDeterminate(
            String phase,
            Component label,
            double targetFraction,
            SharedWorldProgressState previousState,
            Long bytesDone,
            Long bytesTotal
    ) {
        return SharedWorldProgressState.determinate(
                Component.translatable("screen.sharedworld.starting_title"),
                label,
                phase,
                targetFraction,
                previousState,
                bytesDone,
                bytesTotal
        );
    }

    static SharedWorldProgressState savingIndeterminate(String phase, Component label, SharedWorldProgressState previousState) {
        return SharedWorldProgressState.indeterminate(
                Component.translatable("screen.sharedworld.saving_title"),
                label,
                phase,
                previousState
        );
    }

    static SharedWorldProgressState savingDeterminate(
            String phase,
            Component label,
            double targetFraction,
            SharedWorldProgressState previousState,
            Long bytesDone,
            Long bytesTotal
    ) {
        return SharedWorldProgressState.determinate(
                Component.translatable("screen.sharedworld.saving_title"),
                label,
                phase,
                targetFraction,
                previousState,
                bytesDone,
                bytesTotal
        );
    }

    static SharedWorldProgressState releasingState(
            boolean backendFinalizationStarted,
            SharedWorldProgressState previousState
    ) {
        return savingIndeterminate(
                backendFinalizationStarted ? "finishing_up" : "leaving_world",
                Component.translatable(backendFinalizationStarted
                        ? "screen.sharedworld.progress.finishing_up"
                        : "screen.sharedworld.progress.leaving_world"),
                previousState
        );
    }
}
