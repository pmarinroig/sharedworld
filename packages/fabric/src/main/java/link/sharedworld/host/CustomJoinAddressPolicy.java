package link.sharedworld.host;

/**
 * Validation and parsing for the host-local "custom join address" override
 * (VPN/Tailscale-style setups that skip the e4mc tunnel). The address is what
 * guests will connect to verbatim, so it must survive a round trip through
 * the backend (opaque string) and vanilla's ServerAddress.parseString on the
 * guest side: {@code host}, {@code host:port}, or bracketed IPv6.
 */
public final class CustomJoinAddressPolicy {
    public static final int DEFAULT_PORT = 25565;
    /** DNS max name length plus ":65535". */
    private static final int MAX_LENGTH = 260;

    private CustomJoinAddressPolicy() {
    }

    /** Trimmed address, or null when blank/absent. */
    public static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static boolean isValid(String value) {
        String address = normalize(value);
        if (address == null || address.length() > MAX_LENGTH) {
            return false;
        }
        if (address.chars().anyMatch(Character::isWhitespace) || address.contains("/")) {
            return false;
        }
        String host = hostPart(address);
        String port = portPart(address);
        if (host == null || host.isEmpty()) {
            return false;
        }
        return port == null || isValidPort(port);
    }

    /** The port guests will dial; also the LAN port the host publishes on. */
    public static int port(String value) {
        String address = normalize(value);
        if (address == null) {
            return DEFAULT_PORT;
        }
        String port = portPart(address);
        return port != null && isValidPort(port) ? Integer.parseInt(port) : DEFAULT_PORT;
    }

    /** How a host attempt should publish, given the resolved override and e4mc presence. */
    public enum PublishMode {
        E4MC,
        CUSTOM_ADDRESS,
        FAIL_INVALID_ADDRESS,
        FAIL_NEEDS_E4MC_OR_ADDRESS
    }

    public static PublishMode publishMode(String configuredAddress, boolean e4mcAvailable) {
        String address = normalize(configuredAddress);
        if (address == null) {
            return e4mcAvailable ? PublishMode.E4MC : PublishMode.FAIL_NEEDS_E4MC_OR_ADDRESS;
        }
        return isValid(address) ? PublishMode.CUSTOM_ADDRESS : PublishMode.FAIL_INVALID_ADDRESS;
    }

    private static String hostPart(String address) {
        if (address.startsWith("[")) {
            int close = address.indexOf(']');
            return close <= 1 ? null : address.substring(1, close);
        }
        int firstColon = address.indexOf(':');
        int lastColon = address.lastIndexOf(':');
        if (firstColon < 0) {
            return address;
        }
        if (firstColon != lastColon) {
            // Multiple colons without brackets: a bare IPv6 literal, no port.
            return address;
        }
        return address.substring(0, lastColon);
    }

    private static String portPart(String address) {
        if (address.startsWith("[")) {
            int close = address.indexOf(']');
            if (close < 0) {
                return null;
            }
            String rest = address.substring(close + 1);
            if (rest.isEmpty()) {
                return null;
            }
            return rest.startsWith(":") ? rest.substring(1) : "";
        }
        int firstColon = address.indexOf(':');
        int lastColon = address.lastIndexOf(':');
        if (firstColon < 0 || firstColon != lastColon) {
            return null;
        }
        return address.substring(lastColon + 1);
    }

    private static boolean isValidPort(String port) {
        if (port.isEmpty() || port.length() > 5 || !port.chars().allMatch(Character::isDigit)) {
            return false;
        }
        int parsed = Integer.parseInt(port);
        return parsed >= 1 && parsed <= 65535;
    }
}
