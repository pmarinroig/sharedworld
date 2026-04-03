package link.sharedworld.host;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public final class SharedWorldReleaseStore implements SharedWorldReleaseCoordinator.ReleasePersistence {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("sharedworld-release.json");

    @Override
    public synchronized ReleaseRecord load() {
        if (!Files.exists(FILE)) {
            return null;
        }

        try (Reader reader = Files.newBufferedReader(FILE)) {
            return GSON.fromJson(reader, ReleaseRecord.class);
        } catch (IOException exception) {
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
            copy.createdAt = this.createdAt;
            copy.updatedAt = this.updatedAt;
            return copy;
        }
    }
}
