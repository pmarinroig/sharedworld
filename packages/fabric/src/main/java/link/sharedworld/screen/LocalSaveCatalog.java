package link.sharedworld.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.LevelSummary;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

final class LocalSaveCatalog {
    private LocalSaveCatalog() {
    }

    static List<LocalSaveOption> discover() {
        Minecraft minecraft = Minecraft.getInstance();
        LevelStorageSource levelSource = minecraft.getLevelSource();
        try {
            List<LevelSummary> summaries = levelSource.loadLevelSummaries(levelSource.findLevelCandidates()).join();
            List<LocalSaveOption> saves = new ArrayList<>(summaries.size());
            for (LevelSummary summary : summaries) {
                String displayName = summary.getLevelName() == null || summary.getLevelName().isBlank() ? summary.getLevelId() : summary.getLevelName();
                saves.add(new LocalSaveOption(
                        summary.getLevelId(),
                        displayName,
                        levelSource.getLevelPath(summary.getLevelId()),
                        summary.getLastPlayed(),
                        summary.getIcon(),
                        summary.getInfo()
                ));
            }
            saves.sort(Comparator.comparingLong(LocalSaveOption::lastModifiedMillis).reversed());
            return saves;
        } catch (Exception ignored) {
        }

        Path savesRoot = minecraft.gameDirectory.toPath().resolve("saves");
        if (!Files.isDirectory(savesRoot)) {
            return List.of();
        }

        List<LocalSaveOption> saves = new ArrayList<>();
        try (Stream<Path> stream = Files.list(savesRoot)) {
            for (Path candidate : stream.toList()) {
                if (!Files.isDirectory(candidate) || !Files.exists(candidate.resolve("level.dat"))) {
                    continue;
                }
                try {
                    saves.add(new LocalSaveOption(
                            candidate.getFileName().toString(),
                            candidate.getFileName().toString(),
                            candidate,
                            Files.getLastModifiedTime(candidate).toMillis(),
                            Files.isRegularFile(candidate.resolve("icon.png")) ? candidate.resolve("icon.png") : null,
                            Component.literal("")
                    ));
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
            return List.of();
        }

        saves.sort(Comparator.comparingLong(LocalSaveOption::lastModifiedMillis).reversed());
        return saves;
    }

    record LocalSaveOption(
            String id,
            String displayName,
            Path directory,
            long lastModifiedMillis,
            Path iconPath,
            Component metadata
    ) {
    }
}
