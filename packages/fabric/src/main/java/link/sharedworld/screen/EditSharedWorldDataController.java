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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

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
        CompletableFuture.supplyAsync(() -> {
            try {
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
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        }, this.ioExecutor).whenComplete((loaded, error) -> this.mainThreadExecutor.accept(() -> {
            if (error != null) {
                onError.accept(rootCause(error));
                return;
            }
            onSuccess.accept(loaded);
        }));
    }

    void saveDetails(SaveDetailsRequest request, Consumer<WorldDetailsDto> onSuccess, Consumer<Throwable> onError) {
        CompletableFuture.supplyAsync(() -> {
            try {
                String customIconBase64 = SharedWorldMetadataIcons.encodeSelectedIcon(this.iconStore, request.selectedIcon());
                return this.apiClient.updateWorld(
                        request.worldId(),
                        request.name(),
                        request.motd(),
                        null,
                        customIconBase64,
                        request.clearCustomIcon()
                );
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        }, this.ioExecutor).whenComplete((updated, error) -> this.mainThreadExecutor.accept(() -> {
            if (error != null) {
                onError.accept(rootCause(error));
                return;
            }
            onSuccess.accept(updated);
        }));
    }

    void restoreSnapshot(String worldId, String snapshotId, Runnable onSuccess, Consumer<Throwable> onError) {
        CompletableFuture.runAsync(() -> {
            try {
                this.apiClient.restoreSnapshot(worldId, snapshotId);
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        }, this.ioExecutor).whenComplete((ignored, error) -> this.mainThreadExecutor.accept(() -> {
            if (error != null) {
                onError.accept(rootCause(error));
                return;
            }
            onSuccess.run();
        }));
    }

    void deleteSnapshots(String worldId, java.util.List<String> snapshotIds, Runnable onSuccess, Consumer<Throwable> onError) {
        CompletableFuture.runAsync(() -> {
            try {
                this.apiClient.deleteSnapshots(worldId, snapshotIds);
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        }, this.ioExecutor).whenComplete((ignored, error) -> this.mainThreadExecutor.accept(() -> {
            if (error != null) {
                onError.accept(rootCause(error));
                return;
            }
            onSuccess.run();
        }));
    }

    void kickMember(String worldId, String playerUuid, Runnable onSuccess, Consumer<Throwable> onError) {
        CompletableFuture.runAsync(() -> {
            try {
                this.apiClient.kickMember(worldId, playerUuid);
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        }, this.ioExecutor).whenComplete((ignored, error) -> this.mainThreadExecutor.accept(() -> {
            if (error != null) {
                onError.accept(rootCause(error));
                return;
            }
            onSuccess.run();
        }));
    }

    void setMemberCommandPermission(
            String worldId,
            String playerUuid,
            boolean canUseCommands,
            Consumer<WorldMembershipDto> onSuccess,
            Consumer<Throwable> onError
    ) {
        CompletableFuture.supplyAsync(() -> {
            try {
                return this.apiClient.setMemberCommandPermission(worldId, playerUuid, canUseCommands);
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        }, this.ioExecutor).whenComplete((membership, error) -> this.mainThreadExecutor.accept(() -> {
            if (error != null) {
                onError.accept(rootCause(error));
                return;
            }
            onSuccess.accept(membership);
        }));
    }

    void saveSettings(
            String worldId,
            link.sharedworld.api.SharedWorldModels.WorldSettingsDto settings,
            Consumer<WorldDetailsDto> onSuccess,
            Consumer<Throwable> onError
    ) {
        CompletableFuture.supplyAsync(() -> {
            try {
                return this.apiClient.putWorldSettings(worldId, settings);
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        }, this.ioExecutor).whenComplete((details, error) -> this.mainThreadExecutor.accept(() -> {
            if (error != null) {
                onError.accept(rootCause(error));
                return;
            }
            onSuccess.accept(details);
        }));
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

    static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
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
