package link.sharedworld;

import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared body for the per-bucket disconnect mixins
 * (src/versioned/&lt;bucket&gt;/.../mixin/versioned/MinecraftDisconnectMixin). The
 * hooked method differs per Minecraft version, but the decision logic is
 * version-agnostic and lives here so the mixins stay one-line delegations.
 */
public final class SharedWorldDisconnectHook {
    private static final Logger LOGGER = LoggerFactory.getLogger("sharedworld-disconnect");

    private SharedWorldDisconnectHook() {
    }

    public static void onDisconnect(Minecraft minecraft) {
        SharedWorldPlaySessionTracker.ActiveWorldSession session = SharedWorldClient.playSessionTracker().currentSession();
        SharedWorldDisconnectFlow.DisconnectAction action = SharedWorldDisconnectFlow.decide(
                SharedWorldClient.releaseCoordinator().consumeDisconnectPassThrough(),
                minecraft.isLocalServer(),
                SharedWorldClient.hostingManager().activeHostSession() != null,
                session
        );
        switch (action) {
            case IGNORE_PASS_THROUGH -> LOGGER.info("Skipping SharedWorld disconnect detection because release pass-through is armed.");
            case GUEST_ONLY -> {
                LOGGER.info("Observed SharedWorld guest disconnect; tearing the session down as user-initiated.");
                SharedWorldClient.playSessionTracker().markUserInitiatedDisconnect();
                // The intent hook is the teardown authority; the fabric PLAY
                // DISCONNECT event is unreliable on relayed transports (e4mc
                // dialtone never closes the local channel on a manual quit),
                // and a session that survives its own leave later hijacks
                // fresh sessions of the same world.
                SharedWorldClient.onUserInitiatedGuestLeave();
            }
            case HOST_GRACEFUL_RELEASE -> {
                LOGGER.info("Observed SharedWorld host disconnect on a local server; starting graceful release.");
                SharedWorldClient.playSessionTracker().markUserInitiatedDisconnect();
                SharedWorldClient.releaseCoordinator().beginGracefulDisconnect(minecraft);
            }
            case NO_SHAREDWORLD_ACTION -> LOGGER.debug("Observed disconnect without an active SharedWorld host session; no graceful release needed.");
        }
    }
}
