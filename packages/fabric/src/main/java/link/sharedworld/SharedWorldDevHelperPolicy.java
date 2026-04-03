package link.sharedworld;

public final class SharedWorldDevHelperPolicy {
    private static final String DIALTONE_ADDRESS_CLASS = "link.e4mc.dialtone.DialtoneAddress";

    private SharedWorldDevHelperPolicy() {
    }

    public static boolean shouldAllowInsecureDialtoneBypass(String remoteAddressClassName) {
        return DIALTONE_ADDRESS_CLASS.equals(remoteAddressClassName)
                && SharedWorldDevSessionBridge.isInsecureDialtoneBypassAllowed();
    }
}
