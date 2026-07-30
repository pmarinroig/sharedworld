package link.sharedworld.host;

import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WorldSettingsApplierTest {
    @Test
    void difficultyParsesTheBackendValuesAndSkipsUnknowns() {
        assertEquals(Difficulty.PEACEFUL, WorldSettingsApplier.parseDifficulty("peaceful"));
        assertEquals(Difficulty.EASY, WorldSettingsApplier.parseDifficulty("easy"));
        assertEquals(Difficulty.NORMAL, WorldSettingsApplier.parseDifficulty("normal"));
        assertEquals(Difficulty.HARD, WorldSettingsApplier.parseDifficulty("HARD"));
        assertNull(WorldSettingsApplier.parseDifficulty(null));
        assertNull(WorldSettingsApplier.parseDifficulty("impossible"));
    }

    @Test
    void gameModeParsesTheBackendValuesAndSkipsUnknowns() {
        assertEquals(GameType.SURVIVAL, WorldSettingsApplier.parseGameMode("survival"));
        assertEquals(GameType.CREATIVE, WorldSettingsApplier.parseGameMode("Creative"));
        assertEquals(GameType.ADVENTURE, WorldSettingsApplier.parseGameMode("adventure"));
        assertNull(WorldSettingsApplier.parseGameMode(null));
        assertNull(WorldSettingsApplier.parseGameMode("spectator"));
    }

    @Test
    void gameRuleIdsRoundTrip() {
        for (SharedWorldGameRule rule : SharedWorldGameRule.values()) {
            assertEquals(rule, SharedWorldGameRule.byId(rule.id()));
        }
        assertNull(SharedWorldGameRule.byId("doFireTick"));
    }
}
