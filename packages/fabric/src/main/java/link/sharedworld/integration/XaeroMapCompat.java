package link.sharedworld.integration;

import link.sharedworld.host.SharedWorldServerIdentity;
import link.sharedworld.platform.SharedWorldPlatform;
import link.sharedworld.util.ClassProbe;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/**
 * Makes Xaero's Minimap and World Map treat a hosted shared world the way they
 * treat the same world joined as a guest, so a player keeps one map and one
 * waypoint list per world whatever their role.
 *
 * <p>Xaero's keys a singleplayer session by the save folder name and renders the
 * World Map from the save; a multiplayer session is keyed by
 * {@code Multiplayer_<ServerData.ip>} and stores explored regions and waypoints
 * in a {@code mw$<id>} instance, {@code <id>} being what Xaero's server side reads
 * from {@code <world>/xaeromap.txt} and sends on login. SharedWorld syncs that file,
 * so every member already shares the instance id. The mixins in
 * {@code link.sharedworld.mixin.Xaero*} route the host through the multiplayer
 * naming: the root becomes the same {@link SharedWorldJoinIdentity} folder the
 * guest gets, and the waypoint node the same {@code mw$<id>}. Anything that is
 * not a managed SharedWorld working copy is left exactly as Xaero's had it.
 */
public final class XaeroMapCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger(XaeroMapCompat.class);
    public static final String WORLD_ID_FILE = "xaeromap.txt";

    public static final String WORLD_MAP_ROOT_TARGET = "xaero.map.world.MapWorld";
    public static final String WORLD_MAP_ROOT_METHOD = "convertWorldFolderToRootId";
    public static final String WORLD_MAP_ROOT_DESCRIPTOR = "(ILjava/lang/String;)Ljava/lang/String;";
    public static final String MINIMAP_CONTAINER_TARGET = "xaero.hud.minimap.world.container.MinimapWorldContainerUtil";
    public static final String MINIMAP_CONTAINER_METHOD = "convertWorldFolderToContainerNode";
    public static final String MINIMAP_CONTAINER_DESCRIPTOR = "(Ljava/lang/String;I)Ljava/lang/String;";
    public static final String MINIMAP_NODE_TARGET = "xaero.hud.minimap.world.state.MinimapWorldStateUpdater";
    public static final String MINIMAP_NODE_METHOD = "getPotentialWorldNode";

    private XaeroMapCompat() {
    }

    public static boolean isXaeroInstalled() {
        try {
            SharedWorldPlatform platform = SharedWorldPlatform.get();
            return platform.isModLoaded("xaeroworldmap") || platform.isModLoaded("xaerominimap");
        } catch (RuntimeException | LinkageError exception) {
            return false;
        }
    }

    /** Mixin gate: the target class must exist and still define the hooked method. */
    public static boolean hookTargetPresent(String targetClassName, String methodName, String descriptorOrNull) {
        boolean present = ClassProbe.definesMethod(targetClassName, XaeroMapCompat.class.getClassLoader(), methodName, descriptorOrNull);
        LOGGER.info("SharedWorld Xaero's diagnostics [mixin-gate]: target={}#{} present={}", targetClassName, methodName, present);
        return present;
    }

    /**
     * Hook for both mods' "singleplayer folder name to root id" conversion. Returns
     * the multiplayer-style root when {@code worldFolder} is the level id of the
     * managed working copy the integrated server is running, null otherwise.
     */
    public static String hostRootFor(String worldFolder) {
        Path serverRoot = managedServerRoot();
        if (serverRoot == null || worldFolder == null || serverRoot.getFileName() == null) {
            return null;
        }
        if (!worldFolder.equals(serverRoot.getFileName().toString())) {
            return null;
        }
        return SharedWorldJoinIdentity.xaeroMultiplayerRoot(worldFolder);
    }

    /**
     * Hook for the Minimap's per-dimension world node: {@code mw$<id>} for the
     * hosted shared world, matching the instance a guest of this world gets;
     * null leaves Xaero's own logic in charge.
     */
    public static String hostWorldNode() {
        Path serverRoot = managedServerRoot();
        if (serverRoot == null) {
            return null;
        }
        try {
            Integer id = ensureWorldId(serverRoot);
            return id == null ? null : worldNode(id);
        } catch (IOException exception) {
            LOGGER.warn("SharedWorld could not read Xaero's world id in {}", serverRoot, exception);
            return null;
        }
    }

    /**
     * Writes {@code xaeromap.txt} into a working copy about to be hosted when it
     * is missing, exactly as Xaero's server side would on first login, so the id
     * exists before the map opens and travels to every member with the next
     * upload. No-op without Xaero's installed.
     */
    public static void ensureWorldIdFile(Path worldDirectory) {
        if (worldDirectory == null || !isXaeroInstalled()) {
            return;
        }
        try {
            ensureWorldId(worldDirectory);
        } catch (IOException exception) {
            LOGGER.warn("SharedWorld could not write Xaero's world id into {}", worldDirectory, exception);
        }
    }

    static Integer ensureWorldId(Path worldDirectory) throws IOException {
        Path file = worldDirectory.resolve(WORLD_ID_FILE);
        if (Files.isRegularFile(file)) {
            Integer parsed = parseWorldId(Files.readString(file, StandardCharsets.UTF_8));
            if (parsed != null) {
                return parsed;
            }
        }
        if (!Files.isDirectory(worldDirectory)) {
            return null;
        }
        int id = new Random().nextInt();
        Files.writeString(file, "id:" + id, StandardCharsets.UTF_8);
        return id;
    }

    /** Xaero's format: one {@code key:value} per line, {@code id} being the level id. */
    static Integer parseWorldId(String text) {
        for (String line : text.split("\\R")) {
            String[] parts = line.trim().split(":", 2);
            if (parts.length == 2 && "id".equals(parts[0])) {
                try {
                    return Integer.parseInt(parts[1].trim());
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    static String worldNode(int id) {
        return "mw$" + id;
    }

    private static Path managedServerRoot() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return null;
        }
        MinecraftServer server = minecraft.getSingleplayerServer();
        if (server == null || !SharedWorldServerIdentity.isManagedWorldServer(server)) {
            return null;
        }
        return SharedWorldServerIdentity.serverRoot(server);
    }
}
