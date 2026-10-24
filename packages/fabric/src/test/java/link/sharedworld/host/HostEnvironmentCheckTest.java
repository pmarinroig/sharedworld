package link.sharedworld.host;

import link.sharedworld.api.SharedWorldModels.HostModDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HostEnvironmentCheckTest {
    private static final List<HostModDto> LOCAL = List.of(new HostModDto("create", "Create"), new HostModDto("jei", "JEI"));

    @Test
    void sameVersionAndModsReportNothing() {
        assertTrue(HostEnvironmentCheck.compare(4800, "26.3", LOCAL.toArray(HostModDto[]::new), 4800, "26.3", LOCAL).isEmpty());
    }

    @Test
    void aNewerClientReportsTheUpgrade() {
        HostEnvironmentCheck.Changes changes = HostEnvironmentCheck.compare(4700, "26.2", null, 4800, "26.3", LOCAL);
        assertEquals("26.2", changes.versionFrom());
        assertEquals("26.3", changes.versionTo());
        assertFalse(changes.downgrade());
        assertTrue(changes.missingMods().isEmpty());
    }

    @Test
    void anOlderClientReportsTheDowngrade() {
        HostEnvironmentCheck.Changes changes = HostEnvironmentCheck.compare(4900, "26.4", null, 4800, "26.3", LOCAL);
        assertEquals("26.4", changes.versionFrom());
        assertEquals("26.3", changes.versionTo());
        assertTrue(changes.downgrade());
    }

    @Test
    void missingModsAreListedByDisplayNameSortedAndAddedModsAreIgnored() {
        HostModDto[] snapshot = {
                new HostModDto("dynamictrees", "Dynamic Trees"),
                new HostModDto("create", "Create"),
                new HostModDto("aether", null),
                new HostModDto("biomesoplenty", "Biomes O' Plenty")
        };
        HostEnvironmentCheck.Changes changes = HostEnvironmentCheck.compare(4800, "26.3", snapshot, 4800, "26.3", LOCAL);
        assertNull(changes.versionFrom());
        assertEquals(List.of("aether", "Biomes O' Plenty", "Dynamic Trees"), changes.missingMods());
    }

    @Test
    void unknownSnapshotInfoNeverWarns() {
        assertTrue(HostEnvironmentCheck.compare(null, null, null, 4800, "26.3", LOCAL).isEmpty());
    }
}
