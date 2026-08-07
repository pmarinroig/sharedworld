package link.sharedworld.mixin.versioned;

import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.network.DisconnectionDetails;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 1.21.1+ wraps the disconnect reason in DisconnectionDetails; 1.20.1 keeps the raw Component. */
@Mixin(DisconnectedScreen.class)
public interface DisconnectedScreenReasonAccessor {
    @Accessor("details")
    DisconnectionDetails sharedworld$disconnectDetails();
}
