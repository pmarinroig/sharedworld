package link.sharedworld.host;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorldVersionGatePolicyTest {
    @Test
    void hostingBlocksWhenLatestSnapshotIsNewerThanTheClient() {
        assertEquals(
                WorldVersionGatePolicy.HostDecision.BLOCK_SNAPSHOT_NEWER,
                WorldVersionGatePolicy.decideHost(4189, 3465)
        );
    }

    @Test
    void hostingAllowsOlderAndEqualSnapshots() {
        assertEquals(WorldVersionGatePolicy.HostDecision.ALLOW, WorldVersionGatePolicy.decideHost(3465, 4189));
        assertEquals(WorldVersionGatePolicy.HostDecision.ALLOW, WorldVersionGatePolicy.decideHost(4189, 4189));
    }

    @Test
    void hostingAllowsPreGuardrailSnapshotsWithoutVersionStamp() {
        assertEquals(WorldVersionGatePolicy.HostDecision.ALLOW, WorldVersionGatePolicy.decideHost(null, 3465));
    }

    @Test
    void guestJoinBlocksOnDifferentHostVersion() {
        assertEquals(
                WorldVersionGatePolicy.GuestDecision.BLOCK_VERSION_MISMATCH,
                WorldVersionGatePolicy.decideGuestJoin("1.21.11", "1.20.1")
        );
    }

    @Test
    void guestJoinAllowsMatchingOrUnknownVersions() {
        assertEquals(WorldVersionGatePolicy.GuestDecision.ALLOW, WorldVersionGatePolicy.decideGuestJoin("1.21.1", "1.21.1"));
        assertEquals(WorldVersionGatePolicy.GuestDecision.ALLOW, WorldVersionGatePolicy.decideGuestJoin(null, "1.21.1"));
        assertEquals(WorldVersionGatePolicy.GuestDecision.ALLOW, WorldVersionGatePolicy.decideGuestJoin("", "1.21.1"));
        assertEquals(WorldVersionGatePolicy.GuestDecision.ALLOW, WorldVersionGatePolicy.decideGuestJoin("1.21.1", null));
    }
}
