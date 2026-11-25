package link.sharedworld.platform;

import link.sharedworld.api.SharedWorldModels.HostModDto;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.loading.LoadingModList;
import net.minecraftforge.forgespi.language.IModInfo;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Forge 1.20.1 implementation of the loader seam. Replaces FabricPlatform in this
 * jar; instantiated reflectively by {@link SharedWorldPlatform.Holder}.
 */
public final class ForgePlatform implements SharedWorldPlatform {
    public ForgePlatform() {
    }

    @Override
    public Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public boolean isModLoaded(String modId) {
        if (ModList.get() != null) {
            return ModList.get().isLoaded(modId);
        }
        // Early Mixin initialization runs before ModList is constructed.
        LoadingModList loading = LoadingModList.get();
        return loading != null && loading.getModFileById(modId) != null;
    }

    @Override
    public Optional<String> modVersion(String modId) {
        if (ModList.get() != null) {
            return ModList.get()
                    .getModContainerById(modId)
                    .map(container -> container.getModInfo().getVersion().toString());
        }
        // Early Mixin initialization runs before ModList is constructed.
        LoadingModList loading = LoadingModList.get();
        if (loading != null) {
            var file = loading.getModFileById(modId);
            if (file != null && !file.getMods().isEmpty()) {
                return Optional.of(file.getMods().get(0).getVersion().toString());
            }
        }
        return Optional.empty();
    }

    /** Forge has no client-only marker as clean as Fabric's, so every top-level mod counts. */
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

    private static final Set<String> IGNORED_MOD_IDS = Set.of("minecraft", "forge");
}
