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
    void resolvesBackendBaseUrlFromOverrideThenConfigThenDefault() {
        Path configFile = this.tempDir.resolve("sharedworld-client.json");
        SharedWorldClientConfigStore store = new SharedWorldClientConfigStore(configFile);

        assertEquals("http://127.0.0.1:8787", store.resolvedBackendBaseUrl());

        store.setConfiguredBackendBaseUrl("https://configured.example.test///");
        assertEquals("https://configured.example.test", store.resolvedBackendBaseUrl());

        String previous = System.getProperty("sharedworld.backendUrl");
        System.setProperty("sharedworld.backendUrl", "https://override.example.test/");
        try {
            assertEquals("https://override.example.test", store.resolvedBackendBaseUrl());
        } finally {
            if (previous == null) {
                System.clearProperty("sharedworld.backendUrl");
            } else {
                System.setProperty("sharedworld.backendUrl", previous);
            }
        }
    }
}
