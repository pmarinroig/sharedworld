package link.sharedworld.devhelper.e2e;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.lang.reflect.Method;

/**
 * Drives Xaero's Minimap the way a player would, through its own entry points
 * (reflection: the dev-e2e jar does not compile against Xaero's): the
 * "xaero_waypoint_add:" chat command opens Xaero's add-waypoint screen, and the
 * driver then presses that screen's confirm button.
 */
final class XaeroE2eOps {
    private static final String ADD_WAYPOINT_SCREEN = "xaero.common.gui.GuiAddWaypoint";
    private static final String CONFIRM_KEY = "gui.xaero_confirm";

    private XaeroE2eOps() {
    }

    static boolean isInstalled() {
        try {
            Class.forName("xaero.common.HudMod", false, XaeroE2eOps.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError exception) {
            return false;
        }
    }

    /** Opens Xaero's add-waypoint screen for {@code name} at the given block; false when Xaero's refused the command. */
    static boolean beginAddWaypoint(String name, String initials, int x, int y, int z) throws ReflectiveOperationException {
        Class<?> hudMod = Class.forName("xaero.common.HudMod", true, XaeroE2eOps.class.getClassLoader());
        Object instance = hudMod.getField("INSTANCE").get(null);
        Object events = hudMod.getMethod("getEvents").invoke(instance);
        Method handle = events.getClass().getMethod("handleClientSendChatEvent", String.class);
        String command = "xaero_waypoint_add:" + name + ":" + initials + ":" + x + ":" + y + ":" + z + ":0:false:0:External";
        Object consumed = handle.invoke(events, command);
        return !(consumed instanceof Boolean b) || b;
    }

    static boolean isAddWaypointScreen(Screen screen) {
        return screen != null && ADD_WAYPOINT_SCREEN.equals(screen.getClass().getName());
    }

    /** Presses confirm on the add-waypoint screen; true once the screen went away. */
    static boolean confirmAddWaypoint(Minecraft minecraft) {
        if (!isAddWaypointScreen(minecraft.screen)) {
            return minecraft.screen == null;
        }
        WidgetAutomation.pressButton(minecraft.screen, CONFIRM_KEY);
        return false;
    }
}
