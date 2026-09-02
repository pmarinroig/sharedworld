package link.sharedworld;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;

public final class SharedWorldRecoveryStore implements SharedWorldSessionCoordinator.RecoveryPersistence {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;

    public SharedWorldRecoveryStore() {
        this(link.sharedworld.platform.SharedWorldPlatform.get().configDir().resolve("sharedworld-recovery.json"));
    }

    SharedWorldRecoveryStore(Path file) {
        this.file = file;
    }

    @Override
    public synchronized RecoveryRecord load() {
        if (!Files.exists(this.file)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(this.file)) {
            return GSON.fromJson(reader, RecoveryRecord.class);
        } catch (IOException | RuntimeException exception) {
            // A crash mid-write can leave truncated JSON behind; a corrupt
            // record must clear itself instead of crashing every startup.
            clear();
            return null;
        }
    }

    @Override
    public synchronized void save(RecoveryRecord record) throws IOException {
        link.sharedworld.util.AtomicJsonFile.write(this.file, GSON, record);
    }

    @Override
    public synchronized void clear() {
        link.sharedworld.util.AtomicJsonFile.deleteQuietly(this.file);
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
