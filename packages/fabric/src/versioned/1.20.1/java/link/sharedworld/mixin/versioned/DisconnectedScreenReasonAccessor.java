package link.sharedworld.mixin.versioned;

import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 1.20.1 keeps the raw reason Component; 1.21.1+ wraps it in DisconnectionDetails. */
@Mixin(DisconnectedScreen.class)
public interface DisconnectedScreenReasonAccessor {
    @Accessor("reason")
    Component sharedworld$disconnectReason();
}
