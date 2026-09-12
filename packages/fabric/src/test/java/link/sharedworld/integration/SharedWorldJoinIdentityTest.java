package link.sharedworld.integration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class SharedWorldJoinIdentityTest {
    @Test
    void theAddressIsTheWorldIdHexUnderOurDomain() {
        assertEquals("de822dd1dbf74fb091e4776329f94957.sharedworld.link",
                SharedWorldJoinIdentity.serverAddress("world_de822dd1dbf74fb091e4776329f94957"));
        assertEquals("Multiplayer_de822dd1dbf74fb091e4776329f94957.sharedworld.link",
                SharedWorldJoinIdentity.xaeroMultiplayerRoot("world_de822dd1dbf74fb091e4776329f94957"));
    }

    @Test
    void punctuationNeverReachesTheFolderName() {
        // Xaero's Minimap escapes "_" and "/" in folder names and the World Map
        // does not; an address without them keeps both roots identical.
        assertEquals("world1abc.sharedworld.link", SharedWorldJoinIdentity.serverAddress("World-1_ABC/"));
        assertEquals("unknown.sharedworld.link", SharedWorldJoinIdentity.serverAddress(null));
    }
}
