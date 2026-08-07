package link.sharedworld.host;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class WorldSettingsReaderTest {

    @Test
    void nullServerYieldsAnEmptySnapshotWithoutTouchingCompat() {
        // [P9] adjacent: with no server there is nothing to observe; the
        // reader must not reach into the versioned compat seam at all.
        assertTrue(WorldSettingsReader.readGameRules(null).isEmpty());
    }
}
