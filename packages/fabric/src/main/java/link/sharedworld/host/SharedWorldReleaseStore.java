package link.sharedworld.host;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class SharedWorldReleaseStore implements SharedWorldReleaseCoordinator.ReleasePersistence {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;

    public SharedWorldReleaseStore() {
        this(FabricLoader.getInstance().getConfigDir().resolve("sharedworld-release.json"));
    }

    SharedWorldReleaseStore(Path file) {
        this.file = file;
    }

    @Override
    public synchronized ReleaseRecord load() {
        if (!Files.exists(this.file)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(this.file)) {
            return GSON.fromJson(reader, ReleaseRecord.class);
        } catch (IOException | RuntimeException exception) {
            // A crash mid-write can leave truncated JSON behind; a corrupt
            // record must clear itself instead of crashing every startup.
            clear();
            return null;
        }
    }

    @Override
    public synchronized ReleaseRecord loadFor(String worldId, String hostUuid) {
        ReleaseRecord record = load();
        if (record == null) {
            return null;
        }
        if (!equalsIgnoreCase(record.worldId, worldId) || !equalsIgnoreCase(record.hostUuid, hostUuid)) {
            return null;
        }
        return record;
    }

    @Override
    public synchronized void save(ReleaseRecord record) throws IOException {
        Files.createDirectories(this.file.getParent());
        Path tempFile = this.file.resolveSibling(this.file.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(tempFile)) {
            GSON.toJson(record, writer);
        }
        try {
            Files.move(tempFile, this.file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(tempFile, this.file, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    public synchronized void clear() {
        try {
            Files.deleteIfExists(this.file);
        } catch (IOException ignored) {
        }
    }

    private static boolean equalsIgnoreCase(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    public static final class ReleaseRecord {
        public String worldId;
        public String worldName;
        public String hostUuid;
        public long runtimeEpoch;
        public String hostToken;
        public long releaseAttemptId;
        public SharedWorldReleasePhase phase;
        public boolean backendFinalizationStarted;
        public boolean localDisconnectObserved;
        public boolean vanillaDisconnectExpected;
        public boolean finalUploadCompleted;
        public boolean backendFinalizationCompleted;
        public SharedWorldReleasePhase pendingTerminalPhase;
        public int autoRetryCount;
        public String createdAt;
        public String updatedAt;

        public ReleaseRecord copy() {
            ReleaseRecord copy = new ReleaseRecord();
            copy.worldId = this.worldId;
            copy.worldName = this.worldName;
            copy.hostUuid = this.hostUuid;
            copy.runtimeEpoch = this.runtimeEpoch;
            copy.hostToken = this.hostToken;
            copy.releaseAttemptId = this.releaseAttemptId;
            copy.phase = this.phase;
            copy.backendFinalizationStarted = this.backendFinalizationStarted;
            copy.localDisconnectObserved = this.localDisconnectObserved;
            copy.vanillaDisconnectExpected = this.vanillaDisconnectExpected;
            copy.finalUploadCompleted = this.finalUploadCompleted;
            copy.backendFinalizationCompleted = this.backendFinalizationCompleted;
            copy.pendingTerminalPhase = this.pendingTerminalPhase;
            copy.autoRetryCount = this.autoRetryCount;
            copy.createdAt = this.createdAt;
            copy.updatedAt = this.updatedAt;
            return copy;
        }
    }
}
