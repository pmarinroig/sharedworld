package link.sharedworld.integration;
import net.minecraft.SharedConstants;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SharedWorldConnectorTest {
    @BeforeAll
    static void initializeMinecraftVersion() {
        SharedConstants.tryDetectVersion();
    }

    @Test
    void connectPassesJoinTargetThroughTypedConnectStarter() {
        AtomicBoolean invoked = new AtomicBoolean(false);
        AtomicReference<String> addressHost = new AtomicReference<>();
        AtomicReference<Integer> addressPort = new AtomicReference<>();
        AtomicReference<String> serverName = new AtomicReference<>();
        AtomicReference<String> serverIp = new AtomicReference<>();
        AtomicReference<Boolean> quickPlay = new AtomicReference<>();
        AtomicReference<Object> transferState = new AtomicReference<>("unset");

        SharedWorldConnector.connect(
                null,
                "utter-most.de.e4mc.link",
                null,
                "World Name",
                null,
                (parent, minecraft, address, serverData, quickPlayFlag, currentTransferState) -> {
                    invoked.set(true);
                    addressHost.set(address.getHost());
                    addressPort.set(address.getPort());
                    serverName.set(serverData.name);
                    serverIp.set(serverData.ip);
                    quickPlay.set(quickPlayFlag);
                    transferState.set(currentTransferState);
                },
                (parent, error) -> {
                    throw new AssertionError("connect should not open an error screen");
                }
        );

        assertTrue(invoked.get());
        assertEquals(25565, addressPort.get());
        assertEquals("utter-most.de.e4mc.link", addressHost.get());
        assertEquals("World Name", serverName.get());
        assertEquals("utter-most.de.e4mc.link", serverIp.get());
        assertFalse(quickPlay.get());
        assertNull(transferState.get());
    }

    @Test
    void connectShowsVisibleSharedWorldErrorWhenConnectStarterThrows() {
        AtomicBoolean failureHandlerInvoked = new AtomicBoolean(false);

        SharedWorldConnector.connect(
                null,
                "utter-most.de.e4mc.link",
                null,
                "World Name",
                null,
                (parent, minecraft, address, serverData, quickPlay, transferState) -> {
                    throw new IllegalStateException("boom");
                },
                (parent, error) -> failureHandlerInvoked.set(true)
        );

        assertTrue(failureHandlerInvoked.get());
    }

    @Test
    void sourceClearsPendingGuestConnectSetupWhenHandoffFails() throws IOException {
        String source = Files.readString(Path.of(System.getProperty("user.dir"), "src/main/java/link/sharedworld/integration/SharedWorldConnector.java"));

        assertTrue(source.contains("SharedWorldClient.playSessionTracker().clear();"));
    }

    @Test
    void sourceUsesDirectConnectScreenBridgeInsteadOfReflection() throws IOException {
        String source = Files.readString(Path.of(System.getProperty("user.dir"), "src/main/java/link/sharedworld/integration/SharedWorldConnector.java"));

        assertTrue(source.contains("ConnectScreen::startConnecting"));
        assertTrue(source.contains("new SharedWorldErrorScreen("));
        assertTrue(source.contains("screen.sharedworld.join_connect_failed"));
        assertFalse(source.contains("getDeclaredMethods()"));
        assertFalse(source.contains("keyboardHandler.setClipboard"));
        assertFalse(source.contains("Could not locate a compatible ConnectScreen.startConnecting signature"));
    }
}
