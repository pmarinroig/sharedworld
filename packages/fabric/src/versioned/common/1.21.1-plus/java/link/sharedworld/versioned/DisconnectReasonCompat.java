package link.sharedworld.versioned;

import link.sharedworld.mixin.versioned.DisconnectedScreenReasonAccessor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Reads the disconnect reason off a vanilla DisconnectedScreen (DisconnectionDetails on 1.21.1+). */
public final class DisconnectReasonCompat {
    private DisconnectReasonCompat() {
    }

    public static Component disconnectReason(Screen screen) {
        if (!(screen instanceof DisconnectedScreenReasonAccessor accessor)) {
            return null;
        }
        var details = accessor.sharedworld$disconnectDetails();
        return details == null ? null : details.reason();
    }
}
