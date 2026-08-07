package link.sharedworld.versioned;

import link.sharedworld.mixin.versioned.DisconnectedScreenReasonAccessor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Reads the disconnect reason off a vanilla DisconnectedScreen (raw Component on 1.20.1). */
public final class DisconnectReasonCompat {
    private DisconnectReasonCompat() {
    }

    public static Component disconnectReason(Screen screen) {
        return screen instanceof DisconnectedScreenReasonAccessor accessor
                ? accessor.sharedworld$disconnectReason()
                : null;
    }
}
