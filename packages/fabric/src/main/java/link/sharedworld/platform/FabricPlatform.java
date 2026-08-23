package link.sharedworld.platform;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Fabric implementation of the loader seam. Excluded from the NeoForge build,
 * which ships NeoForgePlatform instead; instantiated reflectively by
 * {@link SharedWorldPlatform.Holder}.
 */
public final class FabricPlatform implements SharedWorldPlatform {
    public FabricPlatform() {
    }

    @Override
    public Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    public Optional<String> modVersion(String modId) {
        return FabricLoader.getInstance()
                .getModContainer(modId)
                .map(container -> container.getMetadata().getVersion().getFriendlyString());
    }
}
