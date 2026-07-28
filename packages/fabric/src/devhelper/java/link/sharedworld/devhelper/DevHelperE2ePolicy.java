package link.sharedworld.devhelper;

import link.sharedworld.SharedWorldDevSessionBridge;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * Gate for the loopback login bypass used by the hermetic two-client e2e,
 * where the guest connects to the host's LAN port directly instead of through
 * the e4mc/dialtone transport. Requires all of: the JVM opted in with
 * -Dsharedworld.e2e.insecureLoopback=true (only the e2eHost run config sets
 * it), the connection actually arriving over loopback, and the same
 * dev-session/hosting gates the dialtone bypass uses.
 */
public final class DevHelperE2ePolicy {
    private DevHelperE2ePolicy() {
    }

    public static boolean shouldAllowInsecureLoopbackBypass(SocketAddress remoteAddress) {
        return Boolean.getBoolean("sharedworld.e2e.insecureLoopback")
                && remoteAddress instanceof InetSocketAddress inet
                && inet.getAddress() != null
                && inet.getAddress().isLoopbackAddress()
                && SharedWorldDevSessionBridge.isInsecureDialtoneBypassAllowed();
    }
}
