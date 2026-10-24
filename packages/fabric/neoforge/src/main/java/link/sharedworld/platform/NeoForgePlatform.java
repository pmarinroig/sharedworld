package link.sharedworld.platform;

import link.sharedworld.api.SharedWorldModels.HostModDto;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforgespi.language.IModInfo;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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

    /** NeoForge has no client-only marker as clean as Fabric's, so every top-level mod counts. */
    @Override
    public List<HostModDto> gameplayMods() {
        if (ModList.get() == null) {
            return List.of();
        }
        return ModList.get().getMods().stream()
                .filter(info -> !IGNORED_MOD_IDS.contains(info.getModId()) && !info.getModId().startsWith("sharedworld"))
                .sorted(Comparator.comparing(IModInfo::getModId))
                .map(info -> new HostModDto(info.getModId(), info.getDisplayName()))
                .toList();
    }

    private static final Set<String> IGNORED_MOD_IDS = Set.of("minecraft", "neoforge");
}
