package link.sharedworld;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public final class SharedWorldClientConfigStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private ClientConfig state;

    public SharedWorldClientConfigStore(Path file) {
        this.file = file;
        this.state = load();
    }

    public static SharedWorldClientConfigStore shared() {
        return Holder.INSTANCE;
    }

    public synchronized boolean shouldOpenSharedWorldByDefault() {
        return this.state.ui.lastMultiplayerView == MultiplayerView.SHAREDWORLD;
    }

    public synchronized void rememberSharedWorld() {
        this.state.ui.lastMultiplayerView = MultiplayerView.SHAREDWORLD;
        save();
    }

    public synchronized void rememberVanilla() {
        this.state.ui.lastMultiplayerView = MultiplayerView.VANILLA;
        save();
    }

    public synchronized String configuredBackendBaseUrl() {
        String configured = normalizeBaseUrl(this.state.backend.baseUrl);
        return configured == null ? SharedWorldBackendConstants.DEFAULT_BASE_URL : configured;
    }

    public synchronized void setConfiguredBackendBaseUrl(String baseUrl) {
        this.state.backend.baseUrl = normalizeBaseUrl(baseUrl);
        save();
    }

    public String resolvedBackendBaseUrl() {
        String override = normalizeBaseUrl(System.getProperty(SharedWorldBackendConstants.BACKEND_URL_SYSTEM_PROPERTY));
        if (override != null) {
            return override;
        }
        return configuredBackendBaseUrl();
    }

    /**
     * Custom join address for whenever this computer hosts (VPN/Tailscale
     * setups that skip e4mc), or null to use the e4mc tunnel. Per-computer,
     * not per-world: it describes what *this* machine is reachable as, and any
     * member may end up hosting. Never synced to the backend.
     */
    public synchronized String customJoinAddress() {
        return this.state.hosting.customJoinAddress;
    }

    public synchronized void setCustomJoinAddress(String address) {
        this.state.hosting.customJoinAddress = normalizeCustomJoinAddress(address);
        save();
    }

    /** Account deletion: revert to defaults in memory only (the file is deleted separately). */
    public synchronized void resetForAccountDeletion() {
        this.state = new ClientConfig();
    }

    private ClientConfig load() {
        if (!Files.exists(this.file)) {
            return new ClientConfig();
        }

        try (Reader reader = Files.newBufferedReader(this.file)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            return sanitizeConfig(parseConfig(parsed));
        } catch (IOException | RuntimeException exception) {
            SharedWorldClient.LOGGER.warn("Failed to load SharedWorld client config", exception);
            return new ClientConfig();
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
            SharedWorldClient.LOGGER.warn("Failed to save SharedWorld client config", exception);
        }
    }

    private static ClientConfig parseConfig(JsonElement parsed) {
        if (parsed == null || !parsed.isJsonObject()) {
            return new ClientConfig();
        }
        JsonObject object = parsed.getAsJsonObject();
        if (object.has("backend") || object.has("ui") || object.has("advanced") || object.has("hosting")) {
            ClientConfig loaded = GSON.fromJson(object, ClientConfig.class);
            return loaded == null ? new ClientConfig() : loaded;
        }

        ClientConfig migrated = new ClientConfig();
        migrated.ui.lastMultiplayerView = parseMultiplayerView(object.get("lastMultiplayerView"));
        return migrated;
    }

    private static ClientConfig sanitizeConfig(ClientConfig loaded) {
        ClientConfig sanitized = loaded == null ? new ClientConfig() : loaded;
        if (sanitized.backend == null) {
            sanitized.backend = new BackendConfig();
        }
        sanitized.backend.baseUrl = normalizeBaseUrl(sanitized.backend.baseUrl);
        if (sanitized.ui == null) {
            sanitized.ui = new UiConfig();
        }
        if (sanitized.ui.lastMultiplayerView == null) {
            sanitized.ui.lastMultiplayerView = MultiplayerView.VANILLA;
        }
        if (sanitized.hosting == null) {
            sanitized.hosting = new HostingConfig();
        }
        sanitized.hosting.customJoinAddress = normalizeCustomJoinAddress(sanitized.hosting.customJoinAddress);
        return sanitized;
    }

    private static String normalizeCustomJoinAddress(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static MultiplayerView parseMultiplayerView(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) {
            return MultiplayerView.VANILLA;
        }
        try {
            return MultiplayerView.valueOf(element.getAsString());
        } catch (IllegalArgumentException exception) {
            return MultiplayerView.VANILLA;
        }
    }

    private static String normalizeBaseUrl(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.isEmpty() ? null : trimmed;
    }

    public enum MultiplayerView {
        VANILLA,
        SHAREDWORLD
    }

    private static final class Holder {
        private static final SharedWorldClientConfigStore INSTANCE = new SharedWorldClientConfigStore(
                link.sharedworld.platform.SharedWorldPlatform.get().configDir().resolve("sharedworld-client.json")
        );
    }

    private static final class ClientConfig {
        private BackendConfig backend = new BackendConfig();
        private UiConfig ui = new UiConfig();
        private HostingConfig hosting = new HostingConfig();
    }

    private static final class HostingConfig {
        private String customJoinAddress;
    }

    private static final class BackendConfig {
        private String baseUrl;
    }

    private static final class UiConfig {
        private MultiplayerView lastMultiplayerView = MultiplayerView.VANILLA;
    }
}
