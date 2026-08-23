package link.sharedworld.platform;

import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;
import java.util.Optional;

/**
 * NeoForge implementation of the loader seam. Replaces FabricPlatform in this
 * jar; instantiated reflectively by {@link SharedWorldPlatform.Holder}.
 */
public final class NeoForgePlatform implements SharedWorldPlatform {
    public NeoForgePlatform() {
    }

    @Override
    public Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public boolean isModLoaded(String modId) {
        return ModList.get() != null && ModList.get().isLoaded(modId);
    }

    @Override
    public Optional<String> modVersion(String modId) {
        if (ModList.get() == null) {
            return Optional.empty();
        }
        return ModList.get()
                .getModContainerById(modId)
                .map(container -> container.getModInfo().getVersion().toString());
    }
}
