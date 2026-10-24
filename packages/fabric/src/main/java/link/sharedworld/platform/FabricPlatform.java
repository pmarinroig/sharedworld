package link.sharedworld.platform;

import link.sharedworld.api.SharedWorldModels.HostModDto;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
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

    @Override
    public List<HostModDto> gameplayMods() {
        return FabricLoader.getInstance().getAllMods().stream()
                .filter(container -> container.getContainingMod().isEmpty())
                .map(ModContainer::getMetadata)
                .filter(metadata -> !"builtin".equals(metadata.getType()))
                .filter(metadata -> metadata.getEnvironment().matches(EnvType.SERVER))
                .filter(metadata -> !metadata.getId().startsWith("sharedworld") && !metadata.getId().startsWith("fabric-"))
                .sorted(Comparator.comparing(ModMetadata::getId))
                .map(metadata -> new HostModDto(metadata.getId(), metadata.getName()))
                .toList();
    }
}
