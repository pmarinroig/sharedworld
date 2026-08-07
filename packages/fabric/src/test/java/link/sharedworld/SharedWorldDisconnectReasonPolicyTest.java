package link.sharedworld;

import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SharedWorldDisconnectReasonPolicyTest {
    @Test
    void kickAndBanReasonsAreDeliberateRemovals() {
        assertTrue(SharedWorldDisconnectReasonPolicy.isDeliberateRemoval(
                Component.translatable("multiplayer.disconnect.kicked")));
        assertTrue(SharedWorldDisconnectReasonPolicy.isDeliberateRemoval(
                Component.translatable("multiplayer.disconnect.banned")));
        assertTrue(SharedWorldDisconnectReasonPolicy.isDeliberateRemoval(
                Component.translatable("multiplayer.disconnect.banned.reason", "because")));
        assertTrue(SharedWorldDisconnectReasonPolicy.isDeliberateRemoval(
                Component.translatable("sharedworld.command.ban.disconnected")));
    }

    @Test
    void involuntaryDisconnectsKeepRecovery() {
        assertFalse(SharedWorldDisconnectReasonPolicy.isDeliberateRemoval(null));
        // Connection loss and host shutdown must keep the recovery flow: the
        // shutdown case IS the seamless-handoff entry point.
        assertFalse(SharedWorldDisconnectReasonPolicy.isDeliberateRemoval(
                Component.translatable("disconnect.genericReason", "io error")));
        assertFalse(SharedWorldDisconnectReasonPolicy.isDeliberateRemoval(
                Component.translatable("multiplayer.disconnect.server_shutdown")));
        // A custom free-text kick reason is indistinguishable from any other
        // literal; it stays recoverable by design.
        assertFalse(SharedWorldDisconnectReasonPolicy.isDeliberateRemoval(
                Component.literal("You are banned from this server")));
    }
}
