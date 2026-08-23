package link.sharedworld.screen;

import link.sharedworld.api.SharedWorldApiClient;
import link.sharedworld.api.SharedWorldModels.WorldSnapshotSummaryDto;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/**
 * Owner-side world deletion: backups go one by one so callers can show real
 * progress (a single opaque delete request could sit for a long time on
 * Drive-heavy worlds). Order falls out of the chain rules; the backend
 * refuses the latest backup and delta bases still in use, so each round
 * deletes the current leaves and the final world delete cleans up whatever
 * stayed protected.
 */
final class WorldDeleteRounds {
    interface Progress {
        void onBackupDeleted(int done, int total);
    }

    private WorldDeleteRounds() {
    }

    static void deleteBackupsThenWorld(SharedWorldApiClient api, String worldId, Progress progress) throws Exception {
        List<WorldSnapshotSummaryDto> remaining = new ArrayList<>(Arrays.asList(api.listSnapshots(worldId)));
        int total = remaining.size() + 1;
        int done = 0;
        boolean deletedAnyThisRound = true;
        while (!remaining.isEmpty() && deletedAnyThisRound) {
            deletedAnyThisRound = false;
            for (Iterator<WorldSnapshotSummaryDto> iterator = remaining.iterator(); iterator.hasNext(); ) {
                WorldSnapshotSummaryDto snapshot = iterator.next();
                try {
                    api.deleteSnapshot(worldId, snapshot.snapshotId());
                } catch (SharedWorldApiClient.SharedWorldApiException exception) {
                    if (isProtectedUntilWorldDelete(exception)) {
                        continue;
                    }
                    throw exception;
                }
                iterator.remove();
                done += 1;
                progress.onBackupDeleted(done, total);
                deletedAnyThisRound = true;
            }
        }
        api.deleteWorld(worldId);
        progress.onBackupDeleted(total, total);
    }

    /** The latest backup and in-use delta bases fall with the world itself. */
    static boolean isProtectedUntilWorldDelete(SharedWorldApiClient.SharedWorldApiException exception) {
        return "cannot_delete_latest_snapshot".equals(exception.error())
                || "snapshot_base_in_use".equals(exception.error());
    }
}
