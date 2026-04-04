package link.sharedworld;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SharedWorldE4mcCompatibilityTest {
    @AfterEach
    void tearDown() {
        SharedWorldDevSessionBridge.clear();
    }

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

    @Test
    void e4mcOwnerChecksFollowSharedWorldOwnerIdentityWhileHosting() {
        SharedWorldDevSessionBridge.setHostingSharedWorld(true, "00000000-0000-0000-0000-000000000001");

        assertTrue(SharedWorldE4mcCompatibility.shouldTreatPlayerAsSharedWorldOwnerForE4mc("00000000-0000-0000-0000-000000000001"));
        assertFalse(SharedWorldE4mcCompatibility.shouldTreatPlayerAsSharedWorldOwnerForE4mc("00000000-0000-0000-0000-000000000002"));
    }

    @Test
    void e4mcOwnerChecksStayDisabledWithoutHostedOwnerIdentity() {
        SharedWorldDevSessionBridge.setHostingSharedWorld(true, " ");

        assertFalse(SharedWorldE4mcCompatibility.shouldTreatPlayerAsSharedWorldOwnerForE4mc("00000000-0000-0000-0000-000000000001"));
    }

    @Test
    void detectsIntermediarySingleplayerOwnerCompatTarget() {
        String intermediaryDescriptor = Type.getMethodDescriptor(
                Type.BOOLEAN_TYPE,
                Type.getType(FakeServer.class),
                Type.getType(FakeIntermediaryPlayer.class)
        );
        String namedDescriptor = Type.getMethodDescriptor(
                Type.BOOLEAN_TYPE,
                Type.getType(FakeServer.class),
                Type.getType(FakeNamedPlayer.class)
        );

        assertTrue(SharedWorldE4mcCompatibility.SingleplayerOwnerCompatTarget.INTERMEDIARY
                == SharedWorldE4mcCompatibility.detectSingleplayerOwnerCompatTarget(
                FakeMirrorWithIntermediaryOwnerHook.class.getName(),
                SharedWorldE4mcCompatibilityTest.class.getClassLoader(),
                intermediaryDescriptor,
                namedDescriptor
        ));
    }

    @Test
    void detectsNamedSingleplayerOwnerCompatTarget() {
        String intermediaryDescriptor = Type.getMethodDescriptor(
                Type.BOOLEAN_TYPE,
                Type.getType(FakeServer.class),
                Type.getType(FakeIntermediaryPlayer.class)
        );
        String namedDescriptor = Type.getMethodDescriptor(
                Type.BOOLEAN_TYPE,
                Type.getType(FakeServer.class),
                Type.getType(FakeNamedPlayer.class)
        );

        assertTrue(SharedWorldE4mcCompatibility.SingleplayerOwnerCompatTarget.NAMED
                == SharedWorldE4mcCompatibility.detectSingleplayerOwnerCompatTarget(
                FakeMirrorWithNamedOwnerHook.class.getName(),
                SharedWorldE4mcCompatibilityTest.class.getClassLoader(),
                intermediaryDescriptor,
                namedDescriptor
        ));
    }

    @Test
    void reportsMissingSingleplayerOwnerCompatTargetWhenNoSupportedHookExists() {
        String intermediaryDescriptor = Type.getMethodDescriptor(
                Type.BOOLEAN_TYPE,
                Type.getType(FakeServer.class),
                Type.getType(FakeIntermediaryPlayer.class)
        );
        String namedDescriptor = Type.getMethodDescriptor(
                Type.BOOLEAN_TYPE,
                Type.getType(FakeServer.class),
                Type.getType(FakeNamedPlayer.class)
        );

        assertTrue(SharedWorldE4mcCompatibility.SingleplayerOwnerCompatTarget.MISSING
                == SharedWorldE4mcCompatibility.detectSingleplayerOwnerCompatTarget(
                FakeMirrorWithoutOwnerHook.class.getName(),
                SharedWorldE4mcCompatibilityTest.class.getClassLoader(),
                intermediaryDescriptor,
                namedDescriptor
        ));
        assertTrue(SharedWorldE4mcCompatibility.SingleplayerOwnerCompatTarget.MISSING
                == SharedWorldE4mcCompatibility.detectSingleplayerOwnerCompatTarget(
                "link.sharedworld.test.MissingMirror",
                SharedWorldE4mcCompatibilityTest.class.getClassLoader(),
                intermediaryDescriptor,
                namedDescriptor
        ));
    }

    private static final class FakeServer {
    }

    private static final class FakeIntermediaryPlayer {
    }

    private static final class FakeNamedPlayer {
    }

    private static final class FakeMirrorWithIntermediaryOwnerHook {
        @SuppressWarnings("unused")
        private static boolean isSingleplayerOwner(FakeServer server, FakeIntermediaryPlayer player) {
            return true;
        }
    }

    private static final class FakeMirrorWithNamedOwnerHook {
        @SuppressWarnings("unused")
        private static boolean isSingleplayerOwner(FakeServer server, FakeNamedPlayer player) {
            return true;
        }
    }

    private static final class FakeMirrorWithoutOwnerHook {
        @SuppressWarnings("unused")
        private static boolean isSingleplayerOwner(FakeServer server, FakeIntermediaryPlayer player, String extra) {
            return true;
        }
    }
}
