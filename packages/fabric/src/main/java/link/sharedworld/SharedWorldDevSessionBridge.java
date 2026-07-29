package link.sharedworld;

import java.util.Map;

import link.sharedworld.host.MemberCommandGrant;

public final class SharedWorldDevSessionBridge {
    private static volatile State state = new State(false, false, false, null, Map.of());

    private SharedWorldDevSessionBridge() {
    }

    public static void updateAuthenticatedSession(boolean currentSessionIsDev, boolean backendAllowsInsecureE4mc) {
        State current = state;
        state = new State(
                currentSessionIsDev,
                backendAllowsInsecureE4mc,
                current.hostingSharedWorld(),
                current.hostingSharedWorldOwnerUuid(),
                current.hostedMemberGrants()
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
                normalizeOwnerUuid(hostingSharedWorld ? ownerUuid : null),
                hostingSharedWorld ? current.hostedMemberGrants() : Map.of()
        );
    }

    /**
     * Replace the hosted world's member command grants. Keys must already be
     * {@link link.sharedworld.host.SharedWorldHostPermissionPolicy#commandGrantKey}
     * lookup keys. Ignored (cleared) while not hosting.
     */
    public static void setHostedMemberGrants(Map<String, MemberCommandGrant> grants) {
        State current = state;
        state = new State(
                current.currentSessionIsDev(),
                current.backendAllowsInsecureE4mc(),
                current.hostingSharedWorld(),
                current.hostingSharedWorldOwnerUuid(),
                current.hostingSharedWorld() && grants != null ? Map.copyOf(grants) : Map.of()
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

    public static Map<String, MemberCommandGrant> hostedMemberGrants() {
        return state.hostedMemberGrants();
    }

    public static boolean isInsecureDialtoneBypassAllowed() {
        State current = state;
        return current.currentSessionIsDev()
                && current.backendAllowsInsecureE4mc()
                && current.hostingSharedWorld();
    }

    public static void clear() {
        state = new State(false, false, false, null, Map.of());
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
            String hostingSharedWorldOwnerUuid,
            Map<String, MemberCommandGrant> hostedMemberGrants
    ) {
    }
}
