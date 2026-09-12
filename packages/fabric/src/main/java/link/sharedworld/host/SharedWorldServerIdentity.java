package link.sharedworld.host;

import link.sharedworld.sync.ManagedWorldStore;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The one authoritative answer to "is this integrated server running a SharedWorld-managed world?".
 * Managed worlds are only ever opened from {@code <gameDir>/sharedworld/worlds/<worldId>/<worldId>};
 * anything else (vanilla saves, remote sessions) must stay completely untouched by SharedWorld's
 * publish, settings, and lifecycle machinery.
 */
public final class SharedWorldServerIdentity {
    private SharedWorldServerIdentity() {
    }

    public static Path serverRoot(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
    }

    /** Whether the server is running any managed working copy. */
    public static boolean isManagedWorldServer(MinecraftServer server) {
        if (server == null) {
            return false;
        }
        Path worldsRoot = new ManagedWorldStore().root().toAbsolutePath().normalize();
        return isManagedRoot(serverRoot(server), worldsRoot);
    }

    /** Whether the server is running exactly the given managed working copy. */
    public static boolean isServerForWorkingCopy(MinecraftServer server, Path expectedWorkingCopy) {
        if (server == null || expectedWorkingCopy == null) {
            return false;
        }
        return matchesWorkingCopy(serverRoot(server), expectedWorkingCopy);
    }

    /** {@code <worldsRoot>/<worldId>/<worldId>}: the level id repeats the container name. */
    static boolean isManagedRoot(Path serverRoot, Path worldsRoot) {
        Path container = serverRoot.getParent();
        return container != null
                && worldsRoot.equals(container.getParent())
                && serverRoot.getFileName() != null
                && container.getFileName() != null
                && serverRoot.getFileName().toString().equals(container.getFileName().toString());
    }

    static boolean matchesWorkingCopy(Path serverRoot, Path expectedWorkingCopy) {
        Path expected = expectedWorkingCopy.toAbsolutePath().normalize();
        if (serverRoot.equals(expected)) {
            return true;
        }
        try {
            return Files.exists(serverRoot) && Files.exists(expected) && Files.isSameFile(serverRoot, expected);
        } catch (IOException exception) {
            return false;
        }
    }
}
