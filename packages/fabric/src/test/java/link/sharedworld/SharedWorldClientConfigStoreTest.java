package link.sharedworld;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SharedWorldClientConfigStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void migratesLegacyViewStateIntoStructuredConfig() throws Exception {
        Path configFile = this.tempDir.resolve("sharedworld-client.json");
        Files.writeString(configFile, """
                {
                  "lastMultiplayerView": "SHAREDWORLD"
                }
                """);

        SharedWorldClientConfigStore store = new SharedWorldClientConfigStore(configFile);

        assertTrue(store.shouldOpenSharedWorldByDefault());
        store.setConfiguredBackendBaseUrl("https://local.example.test/");

        String saved = Files.readString(configFile);
        assertTrue(saved.contains("\"backend\""));
        assertTrue(saved.contains("\"baseUrl\": \"https://local.example.test\""));
        assertTrue(saved.contains("\"ui\""));
        assertTrue(saved.contains("\"lastMultiplayerView\": \"SHAREDWORLD\""));
        assertFalse(saved.contains("\"advanced\""));
    }

    @Test
    void customJoinAddressRoundTrips() {
        Path configFile = this.tempDir.resolve("sharedworld-client.json");
        SharedWorldClientConfigStore store = new SharedWorldClientConfigStore(configFile);

        assertEquals(null, store.customJoinAddress());
        store.setCustomJoinAddress("  100.64.0.12:25565 ");

        SharedWorldClientConfigStore reloaded = new SharedWorldClientConfigStore(configFile);
        assertEquals("100.64.0.12:25565", reloaded.customJoinAddress());

        reloaded.setCustomJoinAddress("   ");
        SharedWorldClientConfigStore cleared = new SharedWorldClientConfigStore(configFile);
        assertEquals(null, cleared.customJoinAddress());
    }

    @Test
    void sanitizeDropsBlankCustomJoinAddress() throws Exception {
        Path configFile = this.tempDir.resolve("sharedworld-client.json");
        Files.writeString(configFile, """
                {
                  "backend": {},
                  "ui": {},
                  "hosting": { "customJoinAddress": "  " }
                }
                """);

        SharedWorldClientConfigStore store = new SharedWorldClientConfigStore(configFile);
        assertEquals(null, store.customJoinAddress());

        Files.writeString(configFile, """
                {
                  "backend": {},
                  "ui": {},
                  "hosting": { "customJoinAddress": " vpn.example:2000 " }
                }
                """);
        assertEquals("vpn.example:2000", new SharedWorldClientConfigStore(configFile).customJoinAddress());
    }

    @Test
    void resolvesBackendBaseUrlFromOverrideThenConfigThenDefault() {
        Path configFile = this.tempDir.resolve("sharedworld-client.json");
        SharedWorldClientConfigStore store = new SharedWorldClientConfigStore(configFile);

        assertEquals(SharedWorldBackendConstants.DEFAULT_BASE_URL, store.resolvedBackendBaseUrl());

        store.setConfiguredBackendBaseUrl("https://configured.example.test///");
        assertEquals("https://configured.example.test", store.resolvedBackendBaseUrl());

        String previous = System.getProperty(SharedWorldBackendConstants.BACKEND_URL_SYSTEM_PROPERTY);
        System.setProperty(SharedWorldBackendConstants.BACKEND_URL_SYSTEM_PROPERTY, "https://override.example.test/");
        try {
            assertEquals("https://override.example.test", store.resolvedBackendBaseUrl());
        } finally {
            if (previous == null) {
                System.clearProperty(SharedWorldBackendConstants.BACKEND_URL_SYSTEM_PROPERTY);
            } else {
                System.setProperty(SharedWorldBackendConstants.BACKEND_URL_SYSTEM_PROPERTY, previous);
            }
        }
    }
}
