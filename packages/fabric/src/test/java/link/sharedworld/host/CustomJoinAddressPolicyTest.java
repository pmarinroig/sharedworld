package link.sharedworld.host;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CustomJoinAddressPolicyTest {
    @Test
    void normalizeTrimsAndBlanksToNull() {
        assertNull(CustomJoinAddressPolicy.normalize(null));
        assertNull(CustomJoinAddressPolicy.normalize("   "));
        assertEquals("host:25565", CustomJoinAddressPolicy.normalize("  host:25565 "));
    }

    @Test
    void acceptsCommonAddressShapes() {
        assertTrue(CustomJoinAddressPolicy.isValid("100.64.0.12"));
        assertTrue(CustomJoinAddressPolicy.isValid("100.64.0.12:25565"));
        assertTrue(CustomJoinAddressPolicy.isValid("my-host.tailnet-1234.ts.net"));
        assertTrue(CustomJoinAddressPolicy.isValid("my-host.tailnet-1234.ts.net:2000"));
        assertTrue(CustomJoinAddressPolicy.isValid("fd7a:115c:a1e0::1"));
        assertTrue(CustomJoinAddressPolicy.isValid("[fd7a:115c:a1e0::1]:25565"));
        assertTrue(CustomJoinAddressPolicy.isValid("[::1]"));
    }

    @Test
    void rejectsMalformedAddresses() {
        assertFalse(CustomJoinAddressPolicy.isValid(null));
        assertFalse(CustomJoinAddressPolicy.isValid(""));
        assertFalse(CustomJoinAddressPolicy.isValid("host:"));
        assertFalse(CustomJoinAddressPolicy.isValid("host:0"));
        assertFalse(CustomJoinAddressPolicy.isValid("host:65536"));
        assertFalse(CustomJoinAddressPolicy.isValid("host:port"));
        assertFalse(CustomJoinAddressPolicy.isValid("host with spaces"));
        assertFalse(CustomJoinAddressPolicy.isValid("http://host:25565"));
        assertFalse(CustomJoinAddressPolicy.isValid("[fd7a::1"));
        assertFalse(CustomJoinAddressPolicy.isValid("[]"));
        assertFalse(CustomJoinAddressPolicy.isValid("[fd7a::1]junk"));
        assertFalse(CustomJoinAddressPolicy.isValid(":25565"));
    }

    @Test
    void publishModeCoversAllHostingCombinations() {
        assertEquals(CustomJoinAddressPolicy.PublishMode.E4MC,
                CustomJoinAddressPolicy.publishMode(null, true));
        assertEquals(CustomJoinAddressPolicy.PublishMode.E4MC,
                CustomJoinAddressPolicy.publishMode("  ", true));
        assertEquals(CustomJoinAddressPolicy.PublishMode.FAIL_NEEDS_E4MC_OR_ADDRESS,
                CustomJoinAddressPolicy.publishMode(null, false));
        assertEquals(CustomJoinAddressPolicy.PublishMode.CUSTOM_ADDRESS,
                CustomJoinAddressPolicy.publishMode("100.64.0.12:25565", true));
        assertEquals(CustomJoinAddressPolicy.PublishMode.CUSTOM_ADDRESS,
                CustomJoinAddressPolicy.publishMode("100.64.0.12:25565", false));
        assertEquals(CustomJoinAddressPolicy.PublishMode.FAIL_INVALID_ADDRESS,
                CustomJoinAddressPolicy.publishMode("host:port", true));
        assertEquals(CustomJoinAddressPolicy.PublishMode.FAIL_INVALID_ADDRESS,
                CustomJoinAddressPolicy.publishMode("host:port", false));
    }

    @Test
    void portFallsBackToDefaultMinecraftPort() {
        assertEquals(25565, CustomJoinAddressPolicy.port("100.64.0.12"));
        assertEquals(2000, CustomJoinAddressPolicy.port("100.64.0.12:2000"));
        assertEquals(25565, CustomJoinAddressPolicy.port("fd7a:115c:a1e0::1"));
        assertEquals(25566, CustomJoinAddressPolicy.port("[fd7a:115c:a1e0::1]:25566"));
        assertEquals(25565, CustomJoinAddressPolicy.port(null));
    }
}
