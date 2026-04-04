package link.sharedworld;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SharedWorldDevSessionBridgeTest {
    @AfterEach
    void tearDown() {
        SharedWorldDevSessionBridge.clear();
    }

    @Test
    void helperPolicyRequiresDialtoneHostingDevSessionAndBackendApproval() {
        SharedWorldDevSessionBridge.updateAuthenticatedSession(true, true);
        assertFalse(SharedWorldDevHelperPolicy.shouldAllowInsecureDialtoneBypass("link.e4mc.dialtone.DialtoneAddress"));

        SharedWorldDevSessionBridge.setHostingSharedWorld(true, "00000000-0000-0000-0000-000000000001");
        assertTrue(SharedWorldDevHelperPolicy.shouldAllowInsecureDialtoneBypass("link.e4mc.dialtone.DialtoneAddress"));
        assertFalse(SharedWorldDevHelperPolicy.shouldAllowInsecureDialtoneBypass("java.net.InetSocketAddress"));
    }

    @Test
    void authRefreshKeepsHostingFlagAndOwnerUuidUntilExplicitClear() {
        SharedWorldDevSessionBridge.updateAuthenticatedSession(true, true);
        SharedWorldDevSessionBridge.setHostingSharedWorld(true, "00000000-0000-0000-0000-000000000001");

        SharedWorldDevSessionBridge.updateAuthenticatedSession(true, true);
        assertTrue(SharedWorldDevSessionBridge.isHostingSharedWorld());
        assertEquals("00000000-0000-0000-0000-000000000001", SharedWorldDevSessionBridge.hostingSharedWorldOwnerUuid());
        assertTrue(SharedWorldDevSessionBridge.isInsecureDialtoneBypassAllowed());

        SharedWorldDevSessionBridge.clear();
        assertFalse(SharedWorldDevSessionBridge.isCurrentSessionDev());
        assertFalse(SharedWorldDevSessionBridge.backendAllowsInsecureE4mc());
        assertFalse(SharedWorldDevSessionBridge.isHostingSharedWorld());
        assertNull(SharedWorldDevSessionBridge.hostingSharedWorldOwnerUuid());
    }

    @Test
    void blankOwnerUuidDoesNotPersistHostedOwnerIdentity() {
        SharedWorldDevSessionBridge.setHostingSharedWorld(true, " ");

        assertTrue(SharedWorldDevSessionBridge.isHostingSharedWorld());
        assertNull(SharedWorldDevSessionBridge.hostingSharedWorldOwnerUuid());
    }
}
