package link.sharedworld.host;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SharedWorldServerIdentityTest {
    private static final Path WORLDS_ROOT = Path.of("/game/sharedworld/worlds").toAbsolutePath().normalize();

    @Test
    @DisplayName("[P9] a directory whose name merely starts with the managed root is not managed")
    void prefixCollidingSiblingIsNotManaged() {
        // Path.startsWith is component-wise, so /game/sharedworld/worlds-evil
        // must not count as being inside /game/sharedworld/worlds.
        assertFalse(SharedWorldServerIdentity.isManagedRoot(
                Path.of("/game/sharedworld/worlds-evil/world-1/world-1").toAbsolutePath().normalize(), WORLDS_ROOT));
        assertFalse(SharedWorldServerIdentity.isManagedRoot(
                Path.of("/game/sharedworld/worldsfoo/world-1").toAbsolutePath().normalize(), WORLDS_ROOT));
    }

    @Test
    @DisplayName("[P9] the managed root itself is not a managed world")
    void managedRootItselfIsNotManaged() {
        assertFalse(SharedWorldServerIdentity.isManagedRoot(WORLDS_ROOT, WORLDS_ROOT));
    }

    @Test
    void managedWorkingCopyIsRecognized() {
        assertTrue(SharedWorldServerIdentity.isManagedRoot(
                WORLDS_ROOT.resolve("world-1").resolve("world-1"), WORLDS_ROOT));
    }

    @Test
    void aLegacyCurrentWorkingCopyIsNoLongerOpenedAsManaged() {
        // 0.5.3 renames "current" to the world id before hosting; a leftover
        // "current" must not be mistaken for the live working copy.
        assertFalse(SharedWorldServerIdentity.isManagedRoot(
                WORLDS_ROOT.resolve("world-1").resolve("current"), WORLDS_ROOT));
        assertFalse(SharedWorldServerIdentity.isManagedRoot(
                WORLDS_ROOT.resolve("world-1").resolve("world-2"), WORLDS_ROOT));
    }

    @Test
    void vanillaSaveIsNotManaged() {
        assertFalse(SharedWorldServerIdentity.isManagedRoot(
                Path.of("/game/saves/My World").toAbsolutePath().normalize(), WORLDS_ROOT));
    }

    @Test
    void otherDirectoriesInsideAWorldContainerAreNotManaged() {
        // Only the working copy is ever opened as a world; staging or baseline
        // directories under the container must not count.
        assertFalse(SharedWorldServerIdentity.isManagedRoot(
                WORLDS_ROOT.resolve("world-1").resolve("staging"), WORLDS_ROOT));
    }

    @Test
    void aSaveNamedLikeAWorldIdOutsideTheRootIsNotManaged() {
        assertFalse(SharedWorldServerIdentity.isManagedRoot(
                Path.of("/game/saves/world-1/world-1").toAbsolutePath().normalize(), WORLDS_ROOT));
    }

    @Test
    void matchesWorkingCopyComparesNormalizedPaths() {
        Path workingCopy = WORLDS_ROOT.resolve("world-1").resolve("world-1");
        assertTrue(SharedWorldServerIdentity.matchesWorkingCopy(
                workingCopy, WORLDS_ROOT.resolve("world-1").resolve("x").resolve("..").resolve("world-1")));
        assertFalse(SharedWorldServerIdentity.matchesWorkingCopy(
                workingCopy, WORLDS_ROOT.resolve("world-2").resolve("world-2")));
    }
}
