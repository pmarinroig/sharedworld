package link.sharedworld;

import java.util.Locale;
import java.util.UUID;

public final class CanonicalPlayerIdentity {
    private CanonicalPlayerIdentity() {
    }

    public static String canonicalUuidForAssignment(String backendAssignedPlayerUuid, String currentBackendPlayerUuid) {
        String canonicalUuid = normalizeUuidWithHyphens(backendAssignedPlayerUuid, "backend assigned player UUID");
        String currentUuid = normalizeUuidWithHyphens(currentBackendPlayerUuid, "current backend player UUID");
        if (sameUuid(canonicalUuid, currentUuid)) {
            return canonicalUuid;
        }

        throw new IllegalStateException(
                "SharedWorld backend assigned a host/player UUID that does not match the current authenticated SharedWorld identity. "
                        + "SharedWorld will not continue because using another player's UUID can corrupt playerdata and handoff state."
        );
    }

    public static boolean sameUuid(String left, String right) {
        return stripHyphens(left).equals(stripHyphens(right));
    }

    public static String normalizeUuidWithHyphens(String uuid, String label) {
        if (uuid == null || uuid.isBlank()) {
            throw new IllegalStateException("SharedWorld " + label + " is required.");
        }

        String stripped = stripHyphens(uuid);
        if (stripped.length() != 32 || !stripped.chars().allMatch(CanonicalPlayerIdentity::isHexDigit)) {
            throw new IllegalStateException("SharedWorld " + label + " must be a valid UUID.");
        }

        String hyphenated = stripped.substring(0, 8)
                + "-" + stripped.substring(8, 12)
                + "-" + stripped.substring(12, 16)
                + "-" + stripped.substring(16, 20)
                + "-" + stripped.substring(20, 32);
        return UUID.fromString(hyphenated).toString();
    }

    private static String stripHyphens(String uuid) {
        return uuid.replace("-", "").toLowerCase(Locale.ROOT);
    }

    private static boolean isHexDigit(int value) {
        return (value >= '0' && value <= '9')
                || (value >= 'a' && value <= 'f')
                || (value >= 'A' && value <= 'F');
    }
}
