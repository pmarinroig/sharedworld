package link.sharedworld.host;

import link.sharedworld.SharedWorldDevSessionBridge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

final class SharedWorldHostServerGateTest {
    @AfterEach
    void resetBridge() {
        SharedWorldDevSessionBridge.clear();
    }

    @Test
    @DisplayName("[P9] the gate stays closed without a hosting session, regardless of the server")
    void closedWithoutHostingSession() {
        assertFalse(SharedWorldHostServerGate.isManagedSharedWorldHost(null));
    }

    @Test
    @DisplayName("[P9] a hosting flag alone cannot open the gate; server identity must also match")
    void staleFlagAloneCannotOpenTheGate() {
        SharedWorldDevSessionBridge.setHostingSharedWorld(true);
        // No managed server is running: identity fails, so the stale flag is
        // inert. (A positive identity match requires a booted integrated
        // server and is exercised by the two-client e2e.)
        assertFalse(SharedWorldHostServerGate.isManagedSharedWorldHost(null));
    }
}
