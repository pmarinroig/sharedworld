package link.sharedworld.devhelper.mixin;

import com.mojang.authlib.GameProfile;
import link.sharedworld.RuntimePlayerIdentity;
import link.sharedworld.SharedWorldDevHelperPolicy;
import link.sharedworld.devhelper.DevHelperE2ePolicy;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 1.20.1 has no startClientVerification and its login State enum is not visible to mixins,
 * so instead of cancelling handleHello this variant steers vanilla's own offline branch:
 * the usesAuthentication redirect makes the listener take the READY_TO_ACCEPT path, and the
 * GameProfile redirect substitutes the packet's claimed identity (a complete profile, so
 * the later createFakeProfile offline-UUID derivation is skipped).
 */
@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class ServerLoginPacketListenerDevHelperMixin {
    @Shadow
    @Final
    Connection connection;

    @Redirect(
            method = "handleHello",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/MinecraftServer;usesAuthentication()Z"
            )
    )
    private boolean sharedworld$skipAuthenticationForFakeDialtoneLogin(MinecraftServer server) {
        return server.usesAuthentication() && !this.sharedworld$bypassAllowed();
    }

    @Redirect(
            method = "handleHello",
            at = @At(
                    value = "NEW",
                    target = "(Ljava/util/UUID;Ljava/lang/String;)Lcom/mojang/authlib/GameProfile;"
            )
    )
    private GameProfile sharedworld$useClaimedProfileForFakeDialtoneLogin(
            java.util.UUID profileId,
            String name,
            ServerboundHelloPacket packet
    ) {
        if (this.sharedworld$bypassAllowed()) {
            return RuntimePlayerIdentity.insecureDialtoneProfile(packet);
        }
        return new GameProfile(profileId, name);
    }

    private boolean sharedworld$bypassAllowed() {
        String remoteAddressClassName = this.connection.getRemoteAddress().getClass().getName();
        return SharedWorldDevHelperPolicy.shouldAllowInsecureDialtoneBypass(remoteAddressClassName)
                || DevHelperE2ePolicy.shouldAllowInsecureLoopbackBypass(this.connection.getRemoteAddress());
    }
}
