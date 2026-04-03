package link.sharedworld;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public final class SharedWorldRecoveryStore implements SharedWorldSessionCoordinator.RecoveryPersistence {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("sharedworld-recovery.json");

    @Override
    public synchronized RecoveryRecord load() {
        if (!Files.exists(FILE)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(FILE)) {
            return GSON.fromJson(reader, RecoveryRecord.class);
        } catch (IOException exception) {
            return null;
        }
    }

    @Override
    public synchronized void save(RecoveryRecord record) throws IOException {
        Files.createDirectories(FILE.getParent());
        try (Writer writer = Files.newBufferedWriter(FILE)) {
            GSON.toJson(record, writer);
        }
    }

    @Override
    public synchronized void clear() {
        try {
            Files.deleteIfExists(FILE);
        } catch (IOException ignored) {
        }
    }

    public record RecoveryRecord(
            String worldId,
            String worldName,
            long runtimeEpoch,
            String flowKind,
            String previousJoinTarget,
            String waiterSessionId
    ) {
    }
}
