package link.sharedworld;

import link.sharedworld.api.SharedWorldModels.WorldSummaryDto;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

final class SharedWorldListComparison {
    private SharedWorldListComparison() {
    }

    static boolean orderedWorldsEqual(List<WorldSummaryDto> left, List<WorldSummaryDto> right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null || left.size() != right.size()) {
            return false;
        }
        for (int index = 0; index < left.size(); index++) {
            if (!worldEquals(left.get(index), right.get(index))) {
                return false;
            }
        }
        return true;
    }

    static boolean worldEquals(WorldSummaryDto left, WorldSummaryDto right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return Objects.equals(left.id(), right.id())
                && Objects.equals(left.slug(), right.slug())
                && Objects.equals(left.name(), right.name())
                && Objects.equals(left.ownerUuid(), right.ownerUuid())
                && Objects.equals(left.motd(), right.motd())
                && Objects.equals(left.customIconStorageKey(), right.customIconStorageKey())
                && left.memberCount() == right.memberCount()
                && Objects.equals(left.status(), right.status())
                && Objects.equals(left.lastSnapshotId(), right.lastSnapshotId())
                && Objects.equals(left.lastSnapshotAt(), right.lastSnapshotAt())
                && Objects.equals(left.activeHostUuid(), right.activeHostUuid())
                && Objects.equals(left.activeHostPlayerName(), right.activeHostPlayerName())
                && Objects.equals(left.activeJoinTarget(), right.activeJoinTarget())
                && left.onlinePlayerCount() == right.onlinePlayerCount()
                && Arrays.equals(left.onlinePlayerNames(), right.onlinePlayerNames())
                && Objects.equals(left.storageProvider(), right.storageProvider())
                && left.storageLinked() == right.storageLinked()
                && Objects.equals(left.storageAccountEmail(), right.storageAccountEmail());
    }
}
