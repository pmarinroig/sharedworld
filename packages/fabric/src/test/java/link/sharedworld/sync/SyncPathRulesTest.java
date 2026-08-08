package link.sharedworld.sync;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SyncPathRulesTest {
    private static final String SHARD_CAP_PROPERTY = "sharedworld.dev.superpackShardMaxBytes";

    @AfterEach
    void clearShardCapOverride() {
        System.clearProperty(SHARD_CAP_PROPERTY);
    }

    @Test
    void nonRegionSetUnderTheCapStaysASingleSuperpack() {
        List<PreparedWorldFile> files = List.of(
                nonRegionFile("level.dat", 100),
                nonRegionFile("data/raids.dat", 100)
        );

        assertEquals(List.of(), SyncPathRules.groupSuperpackFiles(files));
    }

    @Test
    void overTheCapGroupsByTopLevelDirectoryWithRootFilesTogether() {
        System.setProperty(SHARD_CAP_PROPERTY, "250");
        List<PreparedWorldFile> files = List.of(
                nonRegionFile("level.dat", 100),
                nonRegionFile("session-notes.txt", 10),
                nonRegionFile("data/raids.dat", 100),
                nonRegionFile("entities/r.0.0.mca", 100)
        );

        List<SyncPathRules.RegionBundleGroup> groups = SyncPathRules.groupSuperpackFiles(files);

        assertEquals(
                List.of("region-bundle:superpack:.", "region-bundle:superpack:data", "region-bundle:superpack:entities"),
                groups.stream().map(SyncPathRules.RegionBundleGroup::bundleId).toList()
        );
        assertEquals(
                List.of("level.dat", "session-notes.txt"),
                groups.get(0).files().stream().map(PreparedWorldFile::relativePath).toList()
        );
    }

    @Test
    void oversizedGroupSplitsRecursivelyAndDeterministically() {
        System.setProperty(SHARD_CAP_PROPERTY, "250");
        List<PreparedWorldFile> files = List.of(
                nonRegionFile("entities/r.0.0.mca", 100),
                nonRegionFile("entities/r.0.1.mca", 100),
                nonRegionFile("entities/r.1.0.mca", 100),
                nonRegionFile("entities/r.1.1.mca", 100)
        );

        List<SyncPathRules.RegionBundleGroup> groups = SyncPathRules.groupSuperpackFiles(files);

        assertEquals(
                List.of("region-bundle:superpack:entities:a", "region-bundle:superpack:entities:b"),
                groups.stream().map(SyncPathRules.RegionBundleGroup::bundleId).toList()
        );
        assertTrue(groups.stream().allMatch(group -> group.files().size() == 2));
        assertEquals(groups, SyncPathRules.groupSuperpackFiles(files));
    }

    @Test
    void singleFileLargerThanTheCapRidesAloneInItsOwnShard() {
        System.setProperty(SHARD_CAP_PROPERTY, "50");
        List<PreparedWorldFile> files = List.of(
                nonRegionFile("datapacks/huge.zip", 500),
                nonRegionFile("level.dat", 10)
        );

        List<SyncPathRules.RegionBundleGroup> groups = SyncPathRules.groupSuperpackFiles(files);

        assertEquals(
                List.of("region-bundle:superpack:.", "region-bundle:superpack:datapacks"),
                groups.stream().map(SyncPathRules.RegionBundleGroup::bundleId).toList()
        );
        assertEquals(1, groups.get(1).files().size());
    }

    @Test
    void shardIdsCannotCollideWithTerrainBundleIds() {
        // A world directory literally named "superpack" still produces terrain
        // ids whose segment before the tile coordinates ends in "region".
        String terrainId = SyncPathRules.regionBundleId("superpack/region/r.1.1.mca");

        assertEquals("region-bundle:superpack/region:1:1", terrainId);
        assertNotEquals("region-bundle:superpack:superpack", terrainId);
        assertTrue(terrainId.startsWith("region-bundle:superpack/"));
    }

    private static PreparedWorldFile nonRegionFile(String relativePath, long size) {
        return new PreparedWorldFile(null, relativePath, "hash-" + relativePath, size, size, "application/octet-stream", false, null);
    }
}
