package link.sharedworld.screen;

import link.sharedworld.SharedWorldCustomIconStore;
import link.sharedworld.SharedWorldCustomIconStore.SelectedIcon;
import link.sharedworld.api.SharedWorldApiClient;
import link.sharedworld.api.SharedWorldModels.WorldDetailsDto;
import link.sharedworld.api.SharedWorldModels.WorldMembershipDto;
import link.sharedworld.api.SharedWorldModels.WorldSnapshotSummaryDto;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import static link.sharedworld.util.Errors.rootCause;

final class EditSharedWorldDataController {
    private final SharedWorldApiClient apiClient;
    private final SharedWorldCustomIconStore iconStore;
    private final Executor ioExecutor;
    private final Consumer<Runnable> mainThreadExecutor;

    EditSharedWorldDataController(
            SharedWorldApiClient apiClient,
            SharedWorldCustomIconStore iconStore,
            Executor ioExecutor,
            Consumer<Runnable> mainThreadExecutor
    ) {
        this.apiClient = apiClient;
        this.iconStore = iconStore;
        this.ioExecutor = ioExecutor;
        this.mainThreadExecutor = mainThreadExecutor;
    }

    void reload(String worldId, Consumer<LoadedState> onSuccess, Consumer<Throwable> onError) {
        submit(() -> {
            WorldDetailsDto loadedDetails = this.apiClient.getWorld(worldId);
            WorldSnapshotSummaryDto[] snapshotArray = this.apiClient.listSnapshots(worldId);
            // 0.4.1 backends stop inlining usage into world details; the
            // dedicated fetch is display-only and must never fail reload.
            link.sharedworld.api.SharedWorldModels.StorageUsageSummaryDto usage = loadedDetails.storageUsage();
            if (usage == null) {
                try {
                    usage = this.apiClient.getStorageUsage(worldId);
                } catch (Exception ignored) {
                    usage = null;
                }
            }
            return new LoadedState(loadedDetails, List.of(snapshotArray), usage);
        }, onSuccess, onError);
    }

    void saveDetails(SaveDetailsRequest request, Consumer<WorldDetailsDto> onSuccess, Consumer<Throwable> onError) {
        submit(() -> {
            String customIconBase64 = SharedWorldMetadataIcons.encodeSelectedIcon(this.iconStore, request.selectedIcon());
            return this.apiClient.updateWorld(
                    request.worldId(),
                    request.name(),
                    request.motd(),
                    null,
                    customIconBase64,
                    request.clearCustomIcon()
            );
        }, onSuccess, onError);
    }

    void restoreSnapshot(String worldId, String snapshotId, Runnable onSuccess, Consumer<Throwable> onError) {
        submit(() -> this.apiClient.restoreSnapshot(worldId, snapshotId), onSuccess, onError);
    }

    void deleteSnapshots(String worldId, java.util.List<String> snapshotIds, Runnable onSuccess, Consumer<Throwable> onError) {
        submit(() -> this.apiClient.deleteSnapshots(worldId, snapshotIds), onSuccess, onError);
    }

    void kickMember(String worldId, String playerUuid, Runnable onSuccess, Consumer<Throwable> onError) {
        submit(() -> this.apiClient.kickMember(worldId, playerUuid), onSuccess, onError);
    }

    void setMemberCommandPermission(
            String worldId,
            String playerUuid,
            boolean canUseCommands,
            Consumer<WorldMembershipDto> onSuccess,
            Consumer<Throwable> onError
    ) {
        submit(() -> this.apiClient.setMemberCommandPermission(worldId, playerUuid, canUseCommands), onSuccess, onError);
    }

    void saveSettings(
            String worldId,
            link.sharedworld.api.SharedWorldModels.WorldSettingsDto settings,
            Consumer<WorldDetailsDto> onSuccess,
            Consumer<Throwable> onError
    ) {
        submit(() -> this.apiClient.putWorldSettings(worldId, settings), onSuccess, onError);
    }

    /** Runs work on the IO executor and delivers its result (or root cause) on the main thread. */
    private <T> void submit(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onError) {
        CompletableFuture.supplyAsync(() -> {
            try {
                return work.call();
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        }, this.ioExecutor).whenComplete((result, error) -> this.mainThreadExecutor.accept(() -> {
            if (error != null) {
                onError.accept(rootCause(error));
                return;
            }
            onSuccess.accept(result);
        }));
    }

    private void submit(ThrowingRunnable work, Runnable onSuccess, Consumer<Throwable> onError) {
        submit(() -> {
            work.run();
            return null;
        }, ignored -> onSuccess.run(), onError);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    static List<WorldMembershipDto> normalizedMemberships(WorldDetailsDto details) {
        List<WorldMembershipDto> memberships = details.memberships() == null
                ? new ArrayList<>()
                : new ArrayList<>(List.of(details.memberships()));
        WorldMembershipDto currentMembership = details.membership();
        if (currentMembership != null) {
            boolean hasCurrentMembership = memberships.stream()
                    .anyMatch(entry -> Objects.equals(entry.playerUuid(), currentMembership.playerUuid()));
            if (!hasCurrentMembership) {
                memberships.add(0, currentMembership);
            }
        }
        return memberships;
    }

    static List<WorldSnapshotSummaryDto> sortedSnapshots(List<WorldSnapshotSummaryDto> snapshots) {
        List<WorldSnapshotSummaryDto> sorted = new ArrayList<>(snapshots);
        sorted.sort(Comparator.comparing(WorldSnapshotSummaryDto::createdAt).reversed());
        return sorted;
    }

    record LoadedState(
            WorldDetailsDto details,
            List<WorldSnapshotSummaryDto> snapshots,
            link.sharedworld.api.SharedWorldModels.StorageUsageSummaryDto storageUsage
    ) {
    }

    record SaveDetailsRequest(
            String worldId,
            String name,
            String motd,
            SelectedIcon selectedIcon,
            boolean clearCustomIcon
    ) {
    }
}
