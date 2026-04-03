package link.sharedworld;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class RuntimePlayerIdentityTest {
    @Test
    void acceptsMatchingDevOverrideAndRuntimeProfileUuid() {
        String resolved = RuntimePlayerIdentity.resolveBackendPlayerUuidWithHyphens(
                "11111111111111111111111111111111",
                UUID.fromString("11111111-1111-1111-1111-111111111111")
        );

        assertEquals("11111111-1111-1111-1111-111111111111", resolved);
    }

    @Test
    void rejectsDivergentDevOverrideAndRuntimeProfileUuid() {
        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> RuntimePlayerIdentity.resolveBackendPlayerUuidWithHyphens(
                        "11111111-1111-1111-1111-111111111111",
                        UUID.fromString("22222222-2222-2222-2222-222222222222")
                )
        );

        assertEquals(
                "SharedWorld dev player UUID override does not match the running Minecraft profile UUID. Launch the dev client with --uuid=11111111-1111-1111-1111-111111111111 so backend auth and in-game playerdata use the same identity.",
                error.getMessage()
        );
    }

    @Test
    void insecureDialtoneLoginUsesPacketProfileUuidInsteadOfOfflineNameUuid() {
        UUID expectedUuid = UUID.fromString("22222222-2222-2222-2222-222222222222");
        ServerboundHelloPacket packet = new ServerboundHelloPacket("GuestB", expectedUuid);

        GameProfile profile = RuntimePlayerIdentity.insecureDialtoneProfile(packet);

        assertEquals(expectedUuid, profile.id());
        assertEquals("GuestB", profile.name());
        assertNotEquals(offlinePlayerUuid("GuestB"), profile.id());
    }

    private static UUID offlinePlayerUuid(String playerName) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName).getBytes(StandardCharsets.UTF_8));
    }
}
