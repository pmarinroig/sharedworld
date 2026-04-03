package link.sharedworld.progress;

import net.minecraft.network.chat.Component;

public record SharedWorldProgressState(
        Component title,
        Component label,
        String phase,
        ProgressMode mode,
        Double targetFraction,
        Double displayedFraction,
        Double lastStableFraction,
        Long bytesDone,
        Long bytesTotal
) {
    public SharedWorldProgressState {
        targetFraction = clamp(targetFraction);
        displayedFraction = clamp(displayedFraction);
        lastStableFraction = clamp(lastStableFraction);
    }

    public static SharedWorldProgressState determinate(
            Component title,
            Component label,
            String phase,
            double targetFraction,
            SharedWorldProgressState previous,
            Long bytesDone,
            Long bytesTotal
    ) {
        double clampedTarget = clamp(targetFraction);
        return new SharedWorldProgressState(
                title,
                label,
                phase,
                ProgressMode.DETERMINATE,
                clampedTarget,
                clampedTarget,
                clampedTarget,
                bytesDone,
                bytesTotal
        );
    }

    public static SharedWorldProgressState indeterminate(
            Component title,
            Component label,
            String phase,
            SharedWorldProgressState previous
    ) {
        return new SharedWorldProgressState(
                title,
                label,
                phase,
                ProgressMode.INDETERMINATE,
                null,
                previous == null ? null : previous.displayedFraction,
                previous == null ? null : (previous.lastStableFraction != null ? previous.lastStableFraction : previous.displayedFraction),
                null,
                null
        );
    }

    private static Double clamp(Double value) {
        if (value == null) {
            return null;
        }
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    private static double clamp(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    public enum ProgressMode {
        DETERMINATE,
        INDETERMINATE
    }
}
