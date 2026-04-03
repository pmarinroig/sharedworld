package link.sharedworld;

public final class SharedWorldDevSessionBridge {
    private static volatile State state = new State(false, false, false);

    private SharedWorldDevSessionBridge() {
    }

    public static void updateAuthenticatedSession(boolean currentSessionIsDev, boolean backendAllowsInsecureE4mc) {
        State current = state;
        state = new State(
                currentSessionIsDev,
                backendAllowsInsecureE4mc,
                current.hostingSharedWorld()
        );
    }

    public static void setHostingSharedWorld(boolean hostingSharedWorld) {
        State current = state;
        state = new State(
                current.currentSessionIsDev(),
                current.backendAllowsInsecureE4mc(),
                hostingSharedWorld
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

    public static boolean isInsecureDialtoneBypassAllowed() {
        State current = state;
        return current.currentSessionIsDev()
                && current.backendAllowsInsecureE4mc()
                && current.hostingSharedWorld();
    }

    public static void clear() {
        state = new State(false, false, false);
    }

    public record State(boolean currentSessionIsDev, boolean backendAllowsInsecureE4mc, boolean hostingSharedWorld) {
    }
}
