package link.sharedworld.host;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;

public final class SharedWorldHostRecoveryStore implements SharedWorldHostingManager.HostRecoveryPersistence {
    private static final Logger LOGGER = LoggerFactory.getLogger("sharedworld-host-recovery");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;

    public SharedWorldHostRecoveryStore() {
        this(link.sharedworld.platform.SharedWorldPlatform.get().configDir().resolve("sharedworld-host-recovery.json"));
    }

    SharedWorldHostRecoveryStore(Path file) {
        this.file = file;
    }

    @Override
    public synchronized SharedWorldHostingManager.HostRecoveryRecord load() {
        if (!Files.exists(this.file)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(this.file)) {
            SharedWorldHostingManager.HostRecoveryRecord record = GSON.fromJson(reader, SharedWorldHostingManager.HostRecoveryRecord.class);
            if (!isValid(record)) {
                LOGGER.warn("SharedWorld host recovery marker was unreadable or invalid; clearing {}", this.file);
                clear();
                return null;
            }
            return record;
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("SharedWorld failed to load host recovery marker {}; clearing corrupted marker", this.file, exception);
            clear();
            return null;
        }
    }

    @Override
    public synchronized void save(SharedWorldHostingManager.HostRecoveryRecord record) throws IOException {
        link.sharedworld.util.AtomicJsonFile.write(this.file, GSON, record);
    }

    @Override
    public synchronized void clear() {
        link.sharedworld.util.AtomicJsonFile.deleteQuietly(this.file);
    }

    private static boolean isValid(SharedWorldHostingManager.HostRecoveryRecord record) {
        return record != null
                && record.worldId() != null
                && !record.worldId().isBlank()
                && record.hostUuid() != null
                && !record.hostUuid().isBlank()
                && record.runtimeEpoch() >= 0L;
    }
}
