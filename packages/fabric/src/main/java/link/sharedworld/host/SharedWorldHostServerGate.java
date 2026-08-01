package link.sharedworld.host;

import link.sharedworld.SharedWorldDevSessionBridge;
import net.minecraft.server.MinecraftServer;

/**
 * The single gate the vanilla-behavior mixins consult ([P9]). SharedWorld's
 * hosting overrides (forced game mode, host permission elevation) apply only
 * when BOTH the hosting-session flag is set AND the server is actually running
 * a SharedWorld-managed working copy. The session flag alone is global mutable
 * state with several clear-sites; ANDing it with {@link SharedWorldServerIdentity}
 * guarantees that a stale flag can never leak SharedWorld behavior into a
 * vanilla singleplayer world.
 */
public final class SharedWorldHostServerGate {
    private SharedWorldHostServerGate() {
    }

    public static boolean isManagedSharedWorldHost(MinecraftServer server) {
        return SharedWorldDevSessionBridge.isHostingSharedWorld()
                && SharedWorldServerIdentity.isManagedWorldServer(server);
    }
}
