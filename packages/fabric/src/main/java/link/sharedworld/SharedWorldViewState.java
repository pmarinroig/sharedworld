package link.sharedworld;

public final class SharedWorldViewState {
    private SharedWorldViewState() {
    }

    public static boolean shouldOpenSharedWorldByDefault() {
        return SharedWorldClientConfigStore.shared().shouldOpenSharedWorldByDefault();
    }

    public static void rememberSharedWorld() {
        SharedWorldClientConfigStore.shared().rememberSharedWorld();
    }

    public static void rememberVanilla() {
        SharedWorldClientConfigStore.shared().rememberVanilla();
    }
}
