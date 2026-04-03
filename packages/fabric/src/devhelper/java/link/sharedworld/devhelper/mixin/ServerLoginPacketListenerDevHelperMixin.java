package link.sharedworld.devhelper.mixin;

import link.sharedworld.RuntimePlayerIdentity;
import link.sharedworld.SharedWorldDevHelperPolicy;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class ServerLoginPacketListenerDevHelperMixin {
    @Shadow
    @Final
    Connection connection;

    @Shadow
    abstract void startClientVerification(com.mojang.authlib.GameProfile gameProfile);

    @Inject(
            method = "handleHello",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/MinecraftServer;usesAuthentication()Z"
            ),
            cancellable = true
    )
    private void sharedworld$allowLocalFakeDialtoneLogin(ServerboundHelloPacket packet, CallbackInfo callbackInfo) {
        String remoteAddressClassName = this.connection.getRemoteAddress().getClass().getName();
        if (!SharedWorldDevHelperPolicy.shouldAllowInsecureDialtoneBypass(remoteAddressClassName)) {
            return;
        }

        this.startClientVerification(RuntimePlayerIdentity.insecureDialtoneProfile(packet));
        callbackInfo.cancel();
    }
}
