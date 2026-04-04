package link.sharedworld;

public final class SharedWorldDevSessionBridge {
    private static volatile State state = new State(false, false, false, null);

    private SharedWorldDevSessionBridge() {
    }

    public static void updateAuthenticatedSession(boolean currentSessionIsDev, boolean backendAllowsInsecureE4mc) {
        State current = state;
        state = new State(
                currentSessionIsDev,
                backendAllowsInsecureE4mc,
                current.hostingSharedWorld(),
                current.hostingSharedWorldOwnerUuid()
        );
    }

    public static void setHostingSharedWorld(boolean hostingSharedWorld) {
        setHostingSharedWorld(hostingSharedWorld, null);
    }

    public static void setHostingSharedWorld(boolean hostingSharedWorld, String ownerUuid) {
        State current = state;
        state = new State(
                current.currentSessionIsDev(),
                current.backendAllowsInsecureE4mc(),
                hostingSharedWorld,
                normalizeOwnerUuid(hostingSharedWorld ? ownerUuid : null)
        );
    }

    public static boolean isCurrentSessionDev() {
        return state.currentSessionIsDev();
    }

    public static boolean backendAllowsInsecureE4mc() {
        return state.backendAllowsInsecureE4mc();
    }

    public static boolean isHostingSharedWorld() {
        return state.hostingSharedWorld();
    }

    public static String hostingSharedWorldOwnerUuid() {
        return state.hostingSharedWorldOwnerUuid();
    }

    public static boolean isInsecureDialtoneBypassAllowed() {
        State current = state;
        return current.currentSessionIsDev()
                && current.backendAllowsInsecureE4mc()
                && current.hostingSharedWorld();
    }

    public static void clear() {
        state = new State(false, false, false, null);
    }

    private static String normalizeOwnerUuid(String ownerUuid) {
        if (ownerUuid == null || ownerUuid.isBlank()) {
            return null;
        }
        return CanonicalPlayerIdentity.normalizeUuidWithHyphens(ownerUuid, "shared world owner UUID");
    }

    public record State(
            boolean currentSessionIsDev,
            boolean backendAllowsInsecureE4mc,
            boolean hostingSharedWorld,
            String hostingSharedWorldOwnerUuid
    ) {
    }
}
