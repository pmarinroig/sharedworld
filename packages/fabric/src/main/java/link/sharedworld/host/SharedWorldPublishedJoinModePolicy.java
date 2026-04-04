package link.sharedworld.host;

import net.minecraft.world.level.GameType;
import org.jspecify.annotations.Nullable;

public final class SharedWorldPublishedJoinModePolicy {
    private SharedWorldPublishedJoinModePolicy() {
    }

    @Nullable
    public static GameType publishGameMode() {
        return null;
    }

    @Nullable
    public static GameType forcedGameMode(@Nullable GameType vanillaForcedGameMode, boolean hostingSharedWorld) {
        if (hostingSharedWorld) {
            return null;
        }
        return vanillaForcedGameMode;
    }
}
