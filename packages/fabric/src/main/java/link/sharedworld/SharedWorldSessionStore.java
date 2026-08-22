package link.sharedworld;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import link.sharedworld.api.SharedWorldApiClient;
import link.sharedworld.api.SharedWorldModels.SessionTokenDto;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists non-dev SharedWorld session tokens across game restarts so a
 * player only touches Mojang's session server once per backend session TTL
 * (168h by default) instead of on every launch. Entries are keyed by
 * (backend base URL, player UUID): a dev override pointing the client at a
 * different backend, or an account switch, never reuses a stale token.
 */
public final class SharedWorldSessionStore implements SharedWorldApiClient.SessionPersistence {
    private static final Logger LOGGER = LoggerFactory.getLogger("sharedworld");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_ENTRIES = 8;

    private final Path file;
    private StoredSessions state;

    public SharedWorldSessionStore(Path file) {
        this.file = file;
        this.state = load();
    }

    public static SharedWorldSessionStore shared() {
        return Holder.INSTANCE;
    }

    @Override
    public synchronized SessionTokenDto load(String baseUrl, String playerUuid) {
        for (StoredSession entry : this.state.sessions) {
            if (matches(entry, baseUrl, playerUuid)) {
                SessionTokenDto session = new SessionTokenDto(entry.token, entry.playerUuid, entry.playerName, entry.expiresAt);
                return hasUsableExpiry(session) ? session : null;
            }
        }
        return null;
    }

    @Override
    public synchronized void save(String baseUrl, String playerUuid, SessionTokenDto session) {
        if (session == null || !hasUsableExpiry(session)) {
            return;
        }
        this.state.sessions.removeIf(entry -> matches(entry, baseUrl, playerUuid));
        StoredSession entry = new StoredSession();
        entry.baseUrl = baseUrl;
        entry.playerUuid = playerUuid;
        entry.token = session.token();
        entry.playerName = session.playerName();
        entry.expiresAt = session.expiresAt();
        this.state.sessions.add(entry);
        while (this.state.sessions.size() > MAX_ENTRIES) {
            this.state.sessions.remove(0);
        }
        save();
    }

    @Override
    public synchronized void clear(String baseUrl, String playerUuid) {
        if (this.state.sessions.removeIf(entry -> matches(entry, baseUrl, playerUuid))) {
            save();
        }
    }

    /**
     * Account deletion: drop every persisted session from memory without
     * rewriting the file — the file itself is deleted right after, and a save
     * here would resurrect it.
     */
    public synchronized void resetForAccountDeletion() {
        this.state = new StoredSessions();
    }

    private static boolean matches(StoredSession entry, String baseUrl, String playerUuid) {
        return entry != null
                && baseUrl != null && baseUrl.equals(entry.baseUrl)
                && playerUuid != null && playerUuid.equals(entry.playerUuid);
    }

    /**
     * A malformed or missing expiry means the entry is unusable. Wall-clock
     * expiry is deliberately NOT checked (here or in the ApiClient): the
     * server is the authority on token lifetime, and trusting the local clock
     * made a skewed clock silently drop (or refuse to save) every session —
     * forcing a full Mojang handshake per launch or per call.
     */
    private static boolean hasUsableExpiry(SessionTokenDto session) {
        if (session.token() == null || session.token().isBlank() || session.expiresAt() == null) {
            return false;
        }
        try {
            Instant.parse(session.expiresAt());
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private StoredSessions load() {
        if (!Files.exists(this.file)) {
            return new StoredSessions();
        }
        try (Reader reader = Files.newBufferedReader(this.file)) {
            StoredSessions loaded = GSON.fromJson(reader, StoredSessions.class);
            if (loaded == null || loaded.version != StoredSessions.CURRENT_VERSION || loaded.sessions == null) {
                return new StoredSessions();
            }
            loaded.sessions.removeIf(entry -> entry == null || entry.baseUrl == null || entry.playerUuid == null);
            return loaded;
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Failed to load persisted SharedWorld sessions", exception);
            return new StoredSessions();
        }
    }

    private void save() {
        try {
            if (this.file.getParent() != null) {
                Files.createDirectories(this.file.getParent());
            }
            try (Writer writer = Files.newBufferedWriter(this.file)) {
                GSON.toJson(this.state, writer);
            }
        } catch (IOException exception) {
            LOGGER.warn("Failed to save persisted SharedWorld sessions", exception);
        }
    }

    private static final class Holder {
        private static final SharedWorldSessionStore INSTANCE = new SharedWorldSessionStore(
                FabricLoader.getInstance().getConfigDir().resolve("sharedworld-sessions.json")
        );
    }

    private static final class StoredSessions {
        private static final int CURRENT_VERSION = 1;
        private int version = CURRENT_VERSION;
        private List<StoredSession> sessions = new ArrayList<>();
    }

    private static final class StoredSession {
        private String baseUrl;
        private String playerUuid;
        private String token;
        private String playerName;
        private String expiresAt;
    }
}
