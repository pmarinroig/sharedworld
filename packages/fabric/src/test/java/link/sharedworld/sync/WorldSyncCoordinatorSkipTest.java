package link.sharedworld.sync;

import link.sharedworld.api.SharedWorldModels;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The no-change autosave skip: the backend proves nothing changed via
 * latestPackIds. The comparison must tolerate a pathological backend answer
 * (duplicated pack ids) by degrading to a needless finalize, never by
 * crashing the sync path.
 */
final class WorldSyncCoordinatorSkipTest {

    private static boolean canSkip(SharedWorldModels.UploadPlanDto plan, Set<String> regionBundleIds) throws Exception {
        Method method = WorldSyncCoordinator.class.getDeclaredMethod(
                "canSkipUnchangedSnapshot", SharedWorldModels.UploadPlanDto.class, Set.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, plan, regionBundleIds);
    }

    private static SharedWorldModels.UploadPackPlanDto presentPack(String packId) {
        return new SharedWorldModels.UploadPackPlanDto(
                new SharedWorldModels.LocalPackDescriptorDto(packId, "hash-" + packId, 1L, 1, null),
                true,
                null, null, null, null, null, null, null, null, null, null
        );
    }

    private static SharedWorldModels.UploadPlanDto plan(String[] latestPackIds) {
        return new SharedWorldModels.UploadPlanDto(
                "world-1",
                "snapshot-base",
                new SharedWorldModels.UploadPlanEntryDto[0],
                presentPack("pack-a"),
                new SharedWorldModels.UploadPackPlanDto[0],
                null,
                latestPackIds
        );
    }

    @Test
    void matchingPackIdsSkipTheFinalize() throws Exception {
        assertTrue(canSkip(plan(new String[]{"pack-a", "bundle-1"}), Set.of("bundle-1")));
    }

    @Test
    void aRemovedLocalPackStillFinalizes() throws Exception {
        assertFalse(canSkip(plan(new String[]{"pack-a", "bundle-1", "bundle-gone"}), Set.of("bundle-1")));
    }

    @Test
    void duplicatedBackendPackIdsDegradeToAFinalizeInsteadOfCrashing() throws Exception {
        // Set.of() would throw IllegalArgumentException here.
        assertTrue(canSkip(plan(new String[]{"pack-a", "bundle-1", "bundle-1"}), Set.of("bundle-1")),
                "duplicates collapse into the same set; a match still skips");
        assertFalse(canSkip(plan(new String[]{"pack-a", "other", "other"}), Set.of("bundle-1")));
    }

    @Test
    void aBackendWithoutLatestPackIdsNeverSkips() throws Exception {
        assertFalse(canSkip(plan(null), Set.of("bundle-1")));
    }
}
