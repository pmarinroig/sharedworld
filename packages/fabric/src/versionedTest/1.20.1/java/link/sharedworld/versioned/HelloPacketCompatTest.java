package link.sharedworld.versioned;

import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class HelloPacketCompatTest {
    @Test
    void exposesNameAndProfileId() {
        UUID uuid = UUID.fromString("22222222-2222-2222-2222-222222222222");
        ServerboundHelloPacket packet = HelloPacketCompat.create("GuestB", uuid);
        assertEquals("GuestB", HelloPacketCompat.name(packet));
        assertEquals(uuid, HelloPacketCompat.profileId(packet));
    }

    @Test
    void emptyProfileIdReadsAsNull() {
        ServerboundHelloPacket packet = new ServerboundHelloPacket("GuestB", Optional.empty());
        assertNull(HelloPacketCompat.profileId(packet));
    }
}
