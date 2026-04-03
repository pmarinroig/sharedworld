package link.sharedworld;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
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

        SharedWorldDevSessionBridge.setHostingSharedWorld(true);
        assertTrue(SharedWorldDevHelperPolicy.shouldAllowInsecureDialtoneBypass("link.e4mc.dialtone.DialtoneAddress"));
        assertFalse(SharedWorldDevHelperPolicy.shouldAllowInsecureDialtoneBypass("java.net.InetSocketAddress"));
    }

    @Test
    void authRefreshKeepsHostingFlagUntilExplicitClear() {
        SharedWorldDevSessionBridge.updateAuthenticatedSession(true, true);
        SharedWorldDevSessionBridge.setHostingSharedWorld(true);

        SharedWorldDevSessionBridge.updateAuthenticatedSession(true, true);
        assertTrue(SharedWorldDevSessionBridge.isHostingSharedWorld());
        assertTrue(SharedWorldDevSessionBridge.isInsecureDialtoneBypassAllowed());

        SharedWorldDevSessionBridge.clear();
        assertFalse(SharedWorldDevSessionBridge.isCurrentSessionDev());
        assertFalse(SharedWorldDevSessionBridge.backendAllowsInsecureE4mc());
        assertFalse(SharedWorldDevSessionBridge.isHostingSharedWorld());
    }
}
