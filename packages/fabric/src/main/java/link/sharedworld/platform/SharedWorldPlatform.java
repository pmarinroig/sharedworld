package link.sharedworld.platform;

import java.nio.file.Path;
import java.util.Optional;

/**
 * The loader seam: everything SharedWorld needs from the mod loader lives
 * behind this interface, so the whole client core compiles for Fabric and
 * NeoForge alike. Each loader's jar ships exactly one implementation
 * ({@link FabricPlatform} on Fabric, NeoForgePlatform on NeoForge) and the
 * holder finds it by probing the class names — no ordering requirement, which
 * matters because the mixin config plugin runs before any mod entrypoint.
 */
public interface SharedWorldPlatform {
    Path configDir();

    boolean isModLoaded(String modId);

    Optional<String> modVersion(String modId);

    static SharedWorldPlatform get() {
        return Holder.instance();
    }

    /** Test seam; production code must never call this. */
    static void setForTesting(SharedWorldPlatform platform) {
        Holder.set(platform);
    }

    final class Holder {
        private static final String[] IMPLEMENTATIONS = {
                "link.sharedworld.platform.FabricPlatform",
                "link.sharedworld.platform.NeoForgePlatform"
        };
        private static volatile SharedWorldPlatform instance;

        private Holder() {
        }

        private static SharedWorldPlatform instance() {
            SharedWorldPlatform current = instance;
            if (current != null) {
                return current;
            }
            synchronized (Holder.class) {
                if (instance == null) {
                    instance = detect();
                }
                return instance;
            }
        }

        private static void set(SharedWorldPlatform platform) {
            synchronized (Holder.class) {
                instance = platform;
            }
        }

        private static SharedWorldPlatform detect() {
            for (String className : IMPLEMENTATIONS) {
                try {
                    Class<?> found = Class.forName(className, true, Holder.class.getClassLoader());
                    return (SharedWorldPlatform) found.getDeclaredConstructor().newInstance();
                } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
                    // Not this loader's jar; try the next candidate.
                } catch (ReflectiveOperationException exception) {
                    throw new IllegalStateException("SharedWorld platform " + className + " could not be created", exception);
                }
            }
            throw new IllegalStateException("SharedWorld found no platform implementation on the classpath");
        }
    }
}
