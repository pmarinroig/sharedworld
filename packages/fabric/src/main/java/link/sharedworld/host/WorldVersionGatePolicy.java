package link.sharedworld.host;

/**
 * Cross-version world guardrail decisions (pure, headless-testable). A world whose latest
 * snapshot was written by a NEWER Minecraft must not be opened by an older client (vanilla
 * would downgrade-corrupt it); older snapshots are fine because vanilla upgrades them.
 * Guests joining a live host on a different Minecraft version cannot connect anyway
 * (protocol mismatch); the gate exists to say so clearly before vanilla's cryptic error.
 */
public final class WorldVersionGatePolicy {
    private WorldVersionGatePolicy() {
    }

    public enum HostDecision {
        ALLOW,
        BLOCK_SNAPSHOT_NEWER
    }

    public enum GuestDecision {
        ALLOW,
        BLOCK_VERSION_MISMATCH
    }

    /** Unknown snapshot versions (pre-guardrail uploads) always allow. */
    public static HostDecision decideHost(Integer lastSnapshotDataVersion, int localDataVersion) {
        if (lastSnapshotDataVersion != null && lastSnapshotDataVersion > localDataVersion) {
            return HostDecision.BLOCK_SNAPSHOT_NEWER;
        }
        return HostDecision.ALLOW;
    }

    /** Unknown host versions (pre-guardrail hosts) always allow. */
    public static GuestDecision decideGuestJoin(String hostMinecraftVersion, String localMinecraftVersion) {
        if (hostMinecraftVersion == null || hostMinecraftVersion.isBlank()
                || localMinecraftVersion == null || localMinecraftVersion.isBlank()) {
            return GuestDecision.ALLOW;
        }
        return hostMinecraftVersion.equals(localMinecraftVersion)
                ? GuestDecision.ALLOW
                : GuestDecision.BLOCK_VERSION_MISMATCH;
    }
}
