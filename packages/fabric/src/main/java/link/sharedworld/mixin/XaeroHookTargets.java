package link.sharedworld.mixin;

import link.sharedworld.util.ClassProbe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Where the Xaero's hooks land, and whether the installed Xaero's still has
 * those methods. Read by the mixin config plugin while Mixin is transforming
 * Minecraft itself, so nothing here may reference a Minecraft class (loading
 * one from the plugin is a re-entrance error); the hook logic lives in
 * {@code link.sharedworld.integration.XaeroMapCompat}, loaded only when a
 * mixin fires.
 */
final class XaeroHookTargets {
    private static final Logger LOGGER = LoggerFactory.getLogger(XaeroHookTargets.class);

    static final String WORLD_MAP_ROOT_TARGET = "xaero.map.world.MapWorld";
    static final String WORLD_MAP_ROOT_METHOD = "convertWorldFolderToRootId";
    static final String WORLD_MAP_ROOT_DESCRIPTOR = "(ILjava/lang/String;)Ljava/lang/String;";
    static final String MINIMAP_CONTAINER_TARGET = "xaero.hud.minimap.world.container.MinimapWorldContainerUtil";
    static final String MINIMAP_CONTAINER_METHOD = "convertWorldFolderToContainerNode";
    static final String MINIMAP_CONTAINER_DESCRIPTOR = "(Ljava/lang/String;I)Ljava/lang/String;";
    static final String MINIMAP_NODE_TARGET = "xaero.hud.minimap.world.state.MinimapWorldStateUpdater";
    static final String MINIMAP_NODE_METHOD = "getPotentialWorldNode";

    private XaeroHookTargets() {
    }

    /** Mixin gate: the target class must exist and still define the hooked method. */
    static boolean present(String targetClassName, String methodName, String descriptorOrNull) {
        boolean present = ClassProbe.definesMethod(targetClassName, XaeroHookTargets.class.getClassLoader(), methodName, descriptorOrNull);
        LOGGER.info("SharedWorld Xaero's diagnostics [mixin-gate]: target={}#{} present={}", targetClassName, methodName, present);
        return present;
    }
}
