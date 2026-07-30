package link.sharedworld.host;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SharedWorldServerIdentityTest {
    private static final Path WORLDS_ROOT = Path.of("/game/sharedworld/worlds").toAbsolutePath().normalize();

    @Test
    void managedWorkingCopyIsRecognized() {
        assertTrue(SharedWorldServerIdentity.isManagedRoot(
                WORLDS_ROOT.resolve("world-1").resolve("current"), WORLDS_ROOT));
    }

    @Test
    void vanillaSaveIsNotManaged() {
        assertFalse(SharedWorldServerIdentity.isManagedRoot(
                Path.of("/game/saves/My World").toAbsolutePath().normalize(), WORLDS_ROOT));
    }

    @Test
    void otherDirectoriesInsideAWorldContainerAreNotManaged() {
        // Only the working copy ("current") is ever opened as a world; staging or
        // baseline directories under the container must not count.
        assertFalse(SharedWorldServerIdentity.isManagedRoot(
                WORLDS_ROOT.resolve("world-1").resolve("staging"), WORLDS_ROOT));
    }

    @Test
    void aSaveNamedCurrentOutsideTheRootIsNotManaged() {
        assertFalse(SharedWorldServerIdentity.isManagedRoot(
                Path.of("/game/saves/current").toAbsolutePath().normalize(), WORLDS_ROOT));
    }

    @Test
    void matchesWorkingCopyComparesNormalizedPaths() {
        Path workingCopy = WORLDS_ROOT.resolve("world-1").resolve("current");
        assertTrue(SharedWorldServerIdentity.matchesWorkingCopy(
                workingCopy, WORLDS_ROOT.resolve("world-1").resolve("x").resolve("..").resolve("current")));
        assertFalse(SharedWorldServerIdentity.matchesWorkingCopy(
                workingCopy, WORLDS_ROOT.resolve("world-2").resolve("current")));
    }
}
