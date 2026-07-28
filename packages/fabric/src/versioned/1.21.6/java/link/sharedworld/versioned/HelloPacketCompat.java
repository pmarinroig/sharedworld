package link.sharedworld.versioned;

import net.minecraft.network.protocol.login.ServerboundHelloPacket;

import java.util.UUID;

/** Version-specific hello-packet field access (profileId became Optional-free after 1.20.x). */
public final class HelloPacketCompat {
    private HelloPacketCompat() {
    }

    /** Returns the client-claimed profile UUID, or null when the packet carries none. */
    public static UUID profileId(ServerboundHelloPacket packet) {
        return packet.profileId();
    }

    public static String name(ServerboundHelloPacket packet) {
        return packet.name();
    }

    /** Builds a hello packet (test/support factory). */
    public static ServerboundHelloPacket create(String name, UUID profileId) {
        return new ServerboundHelloPacket(name, profileId);
    }
}
