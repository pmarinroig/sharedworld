package link.sharedworld.host;

import link.sharedworld.api.SharedWorldModels.HostModDto;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Host-start environment check (pure, headless-testable): what changed between the
 * client that wrote the latest snapshot and this one, in the ways that change the world
 * for good once it opens. Opening on a newer Minecraft upgrades it (older clients can no
 * longer host it); opening on an older one can break it; opening without a mod deletes that
 * mod's blocks and items. The player is warned, never blocked. Mods that were added or
 * changed version are not reported; adding content is normally safe.
 */
public final class HostEnvironmentCheck {
    private HostEnvironmentCheck() {
    }

    /**
     * @param versionFrom the version the world was last played on, when this client's differs; else null
     * @param versionTo this client's version when versionFrom is set; else null
     * @param downgrade whether versionTo is older than versionFrom
     * @param missingMods display names of mods the last host had and this client lacks, sorted
     */
    public record Changes(String versionFrom, String versionTo, boolean downgrade, List<String> missingMods) {
        public boolean isEmpty() {
            return this.versionFrom == null && this.missingMods.isEmpty();
        }
    }

    /**
     * Unknown inputs never warn: a null snapshot data version (pre-guardrail upload) skips
     * the version comparison, and null snapshot mods (pre-0.5.4 host) skip the mod one.
     */
    public static Changes compare(
            Integer snapshotDataVersion,
            String snapshotMinecraftVersion,
            HostModDto[] snapshotMods,
            int localDataVersion,
            String localMinecraftVersion,
            List<HostModDto> localMods
    ) {
        boolean changed = snapshotDataVersion != null && snapshotDataVersion != localDataVersion;
        String from = null;
        String to = null;
        if (changed) {
            from = snapshotMinecraftVersion == null || snapshotMinecraftVersion.isBlank()
                    ? String.valueOf(snapshotDataVersion)
                    : snapshotMinecraftVersion;
            to = localMinecraftVersion == null || localMinecraftVersion.isBlank()
                    ? String.valueOf(localDataVersion)
                    : localMinecraftVersion;
        }
        List<String> missing = new ArrayList<>();
        if (snapshotMods != null) {
            Set<String> localIds = new HashSet<>();
            for (HostModDto mod : localMods) {
                localIds.add(mod.id());
            }
            for (HostModDto mod : snapshotMods) {
                if (mod != null && mod.id() != null && !mod.id().isBlank() && !localIds.contains(mod.id())) {
                    missing.add(mod.name() == null || mod.name().isBlank() ? mod.id() : mod.name());
                }
            }
            missing.sort((a, b) -> a.toLowerCase(Locale.ROOT).compareTo(b.toLowerCase(Locale.ROOT)));
        }
        return new Changes(from, to, changed && snapshotDataVersion > localDataVersion, List.copyOf(missing));
    }
}
