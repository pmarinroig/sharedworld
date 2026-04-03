package link.sharedworld.mixin;

import io.netty.channel.ChannelHandlerContext;
import link.sharedworld.integration.E4mcDomainTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;

@Pseudo
@Mixin(targets = "link.e4mc.QuiclimeSession$2$1")
public abstract class E4mcQuiclimeSessionMixin {
    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Ljava/lang/Object;)V", at = @At("HEAD"))
    private void sharedworld$captureAssignedDomain(ChannelHandlerContext context, Object message, CallbackInfo callbackInfo) {
        if (message == null) {
            return;
        }
        if (!message.getClass().getName().endsWith("DomainAssignmentCompleteMessageClientbound")) {
            return;
        }

        try {
            Field field = message.getClass().getDeclaredField("domain");
            field.setAccessible(true);
            Object value = field.get(message);
            if (value instanceof String domain) {
                E4mcDomainTracker.captureAssignedDomain(domain);
            }
        } catch (ReflectiveOperationException ignored) {
        }
    }
}
