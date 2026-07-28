package link.sharedworld.integration;
import net.minecraft.SharedConstants;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

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
        AtomicReference<String> startedTarget = new AtomicReference<>();
        AtomicReference<String> startedWorldName = new AtomicReference<>();

        SharedWorldConnector.connect(
                null,
                "utter-most.de.e4mc.link",
                null,
                "World Name",
                0L,
                null,
                (parent, minecraft, target, worldName) -> {
                    invoked.set(true);
                    startedTarget.set(target);
                    startedWorldName.set(worldName);
                },
                (parent, error) -> {
                    throw new AssertionError("connect should not open an error screen");
                }
        );

        assertTrue(invoked.get());
        assertEquals("utter-most.de.e4mc.link", startedTarget.get());
        assertEquals("World Name", startedWorldName.get());
    }

    @Test
    void connectShowsVisibleSharedWorldErrorWhenConnectStarterThrows() {
        AtomicBoolean failureHandlerInvoked = new AtomicBoolean(false);

        SharedWorldConnector.connect(
                null,
                "utter-most.de.e4mc.link",
                null,
                "World Name",
                0L,
                null,
                (parent, minecraft, target, worldName) -> {
                    throw new IllegalStateException("boom");
                },
                (parent, error) -> failureHandlerInvoked.set(true)
        );

        assertTrue(failureHandlerInvoked.get());
    }
}
