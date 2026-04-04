package link.sharedworld;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SharedWorldE4mcCompatibilityTest {
    @Test
    void parsesSemanticVersionTripletsWithSuffixes() {
        assertArrayEquals(new int[]{6, 1, 0}, SharedWorldE4mcCompatibility.parseVersionTriplet("6.1.0"));
        assertArrayEquals(new int[]{6, 1, 0}, SharedWorldE4mcCompatibility.parseVersionTriplet("6.1.0+fabric"));
        assertArrayEquals(new int[]{6, 0, 6}, SharedWorldE4mcCompatibility.parseVersionTriplet("e4mc-6.0.6"));
    }

    @Test
    void returnsNullWhenNoSemanticTripletExists() {
        assertNull(SharedWorldE4mcCompatibility.parseVersionTriplet(null));
        assertNull(SharedWorldE4mcCompatibility.parseVersionTriplet(""));
        assertNull(SharedWorldE4mcCompatibility.parseVersionTriplet("missing"));
    }

    @Test
    void onlyAppliesServerboundCompatMixinBeforeE4mc610() {
        assertTrue(SharedWorldE4mcCompatibility.shouldApplyServerboundKeyPacketCompatMixin("6.0.6"));
        assertTrue(SharedWorldE4mcCompatibility.shouldApplyServerboundKeyPacketCompatMixin("5.9.9"));
        assertFalse(SharedWorldE4mcCompatibility.shouldApplyServerboundKeyPacketCompatMixin("6.1.0"));
        assertFalse(SharedWorldE4mcCompatibility.shouldApplyServerboundKeyPacketCompatMixin("6.1.0+fabric"));
        assertFalse(SharedWorldE4mcCompatibility.shouldApplyServerboundKeyPacketCompatMixin("6.1.1"));
        assertFalse(SharedWorldE4mcCompatibility.shouldApplyServerboundKeyPacketCompatMixin("missing"));
    }
}
