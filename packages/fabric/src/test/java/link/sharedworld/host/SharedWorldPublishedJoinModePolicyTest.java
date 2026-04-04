package link.sharedworld.host;

import net.minecraft.world.level.GameType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class SharedWorldPublishedJoinModePolicyTest {
    @Test
    void publishGameModeDoesNotForceLanJoinersIntoASharedWorldMode() {
        assertNull(SharedWorldPublishedJoinModePolicy.publishGameMode());
    }

    @Test
    void forcedGameModePreservesSavedPlayerStateWhileHostingSharedWorld() {
        assertNull(SharedWorldPublishedJoinModePolicy.forcedGameMode(GameType.SURVIVAL, true));
    }

    @Test
    void forcedGameModeLeavesVanillaBehaviorAloneOutsideSharedWorldHosting() {
        assertEquals(GameType.CREATIVE, SharedWorldPublishedJoinModePolicy.forcedGameMode(GameType.CREATIVE, false));
    }
}
