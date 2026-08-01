package link.sharedworld.host;

import net.minecraft.world.level.GameType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Pins the inertness half of the published-join-mode policy: when no
 * SharedWorld hosting session is active, the policy must return the vanilla
 * value untouched for every possible input.
 */
final class VanillaInertnessTest {
    @Test
    @DisplayName("[P9] a non-hosting session passes every vanilla forced game mode through unchanged")
    void nonHostingSessionKeepsVanillaForcedGameMode() {
        for (GameType vanilla : GameType.values()) {
            assertSame(vanilla, SharedWorldPublishedJoinModePolicy.forcedGameMode(vanilla, false));
        }
        assertNull(SharedWorldPublishedJoinModePolicy.forcedGameMode(null, false));
    }

    @Test
    @DisplayName("[P9] a hosting session suppresses the vanilla forced game mode")
    void hostingSessionSuppressesForcedGameMode() {
        for (GameType vanilla : GameType.values()) {
            assertNull(SharedWorldPublishedJoinModePolicy.forcedGameMode(vanilla, true));
        }
        assertNull(SharedWorldPublishedJoinModePolicy.forcedGameMode(null, true));
    }
}
