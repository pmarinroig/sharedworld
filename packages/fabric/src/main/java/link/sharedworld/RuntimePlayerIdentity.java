package link.sharedworld;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.User;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;

import java.util.Objects;
import java.util.UUID;

public final class RuntimePlayerIdentity {
    private RuntimePlayerIdentity() {
    }

    public static String resolveBackendPlayerUuidWithHyphens(User user) {
        Objects.requireNonNull(user, "user");
        return resolveBackendPlayerUuidWithHyphens(
                System.getProperty("sharedworld.devPlayerUuid"),
                user.getProfileId()
        );
    }

    static String resolveBackendPlayerUuidWithHyphens(String configuredDevPlayerUuid, UUID runtimeProfileUuid) {
        Objects.requireNonNull(runtimeProfileUuid, "runtimeProfileUuid");
        if (configuredDevPlayerUuid == null || configuredDevPlayerUuid.isBlank()) {
            return runtimeProfileUuid.toString();
        }

        String normalizedDevUuid = CanonicalPlayerIdentity.normalizeUuidWithHyphens(
                configuredDevPlayerUuid,
                "dev player UUID"
        );
        String runtimeUuid = runtimeProfileUuid.toString();
        if (!CanonicalPlayerIdentity.sameUuid(normalizedDevUuid, runtimeUuid)) {
            throw new IllegalStateException(
                    "SharedWorld dev player UUID override does not match the running Minecraft profile UUID. "
                            + "Launch the dev client with --uuid=" + normalizedDevUuid
                            + " so backend auth and in-game playerdata use the same identity."
            );
        }
        return normalizedDevUuid;
    }

    public static GameProfile insecureDialtoneProfile(ServerboundHelloPacket packet) {
        Objects.requireNonNull(packet, "packet");
        UUID profileId = Objects.requireNonNull(
                packet.profileId(),
                "SharedWorld dev insecure login requires a client profile UUID."
        );
        return new GameProfile(profileId, packet.name());
    }
}
