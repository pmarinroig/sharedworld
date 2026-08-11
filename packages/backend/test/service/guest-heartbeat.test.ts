import { describe, expect, test } from "bun:test";

import { actor, makeCoordinator, member } from "../support/realtime.ts";
import { createBlobSigner, createTestService } from "../support/service-fixtures.ts";
import { createSqliteRepository } from "../support/sqlite-d1.ts";

/**
 * EB4: POST /worlds/:id/presence answers with the flat GuestHeartbeatResponse
 * superset — presence ack + resolved runtime + lastSnapshotId — so one
 * 0.4.1+ guest beat replaces three separate polls. Older clients bind the
 * same body to the old 5-field shape; every extra field is additive.
 */
describe("merged guest heartbeat", () => {
  const owner = { playerUuid: "player-owner", playerName: "Owner" };

  async function fixture() {
    const repository = createSqliteRepository();
    const instance = createTestService(repository, createBlobSigner().signer, {});
    await repository.upsertUser({ ...owner, createdAt: new Date().toISOString() });
    const world = await repository.createWorld(owner, "Friends SMP", "friends-smp");
    return { repository, instance, world };
  }

  test("the response is a presence ack plus runtime status plus lastSnapshotId", async () => {
    const { repository, instance, world } = await fixture();
    const now = new Date("2099-01-01T01:00:00.000Z");

    const idle = await instance.setPlayerPresence(owner, world.id, { present: true, guestSessionEpoch: 1, presenceSequence: 1 }, now);
    expect(idle.worldId).toBe(world.id);
    expect(idle.present).toBe(true);
    expect(idle.updatedAt).toBe(now.toISOString());
    expect(idle.phase).toBe("idle");
    expect(idle.runtimeEpoch).toBe(0);
    expect(idle.hostUuid).toBeNull();
    expect(idle.lastSnapshotId).toBeNull();

    await repository.finalizeSnapshot(world.id, owner, { files: [], packs: [] }, new Date("2099-01-01T01:30:00.000Z"));
    const withSnapshot = await instance.setPlayerPresence(owner, world.id, { present: true, guestSessionEpoch: 1, presenceSequence: 2 }, new Date("2099-01-01T02:00:00.000Z"));
    expect(withSnapshot.lastSnapshotId).not.toBeNull();
  });

  test("a live host shows up in the beat's runtime fields", async () => {
    const { instance, world } = await fixture();
    await instance.claimHost(owner, world.id, { joinTarget: "example.test:25565" }, new Date("2099-01-01T00:00:00.000Z"));

    const beat = await instance.setPlayerPresence(owner, world.id, { present: true, guestSessionEpoch: 1, presenceSequence: 1 }, new Date("2099-01-01T00:00:10.000Z"));
    expect(beat.phase).toBe("host-live");
    expect(beat.hostUuid).toBe(owner.playerUuid);
    expect(beat.joinTarget).toBe("example.test:25565");
    expect(beat.runtimeEpoch).toBeGreaterThan(0);
  });

  test("membership errors keep their exact authority semantics", async () => {
    const { repository, instance, world } = await fixture();
    await repository.addMembership({
      worldId: world.id,
      playerUuid: "player-guest",
      playerName: "Guest",
      role: "member",
      joinedAt: new Date().toISOString(),
      deletedAt: null,
      canUseCommands: false
    });
    await repository.kickMember(world.id, "player-guest", new Date().toISOString());

    const beat = { present: true, guestSessionEpoch: 1, presenceSequence: 1 };
    await expect(instance.setPlayerPresence({ playerUuid: "player-guest", playerName: "Guest" }, world.id, beat))
      .rejects.toMatchObject({ status: 403, code: "membership_revoked" });
    await expect(instance.setPlayerPresence({ playerUuid: "player-outsider", playerName: "Outsider" }, world.id, beat))
      .rejects.toMatchObject({ status: 403, code: "forbidden" });
    await expect(instance.setPlayerPresence(owner, "world_missing", beat))
      .rejects.toMatchObject({ status: 404, code: "world_not_found" });
  });

  test("coordinator-side: epoch/sequence fencing is unchanged and beats stay cheap", async () => {
    const { coordinator, effects } = makeCoordinator();
    effects.memberships = [member("player-guest", "Guest", "member", "2026-01-01T00:00:00.000Z")];
    const guest = actor("player-guest", "Guest");

    const first = await coordinator.guestHeartbeat(guest, { present: true, guestSessionEpoch: 2, presenceSequence: 1 }, new Date("2026-01-01T10:00:00.000Z"));
    expect(first.phase).toBe("idle");
    expect(coordinator.roomPlayers(new Date("2026-01-01T10:00:01.000Z"))).toHaveLength(1);

    // A stale session's "gone" report cannot resurrect/clear the newer one.
    await coordinator.guestHeartbeat(guest, { present: false, guestSessionEpoch: 1, presenceSequence: 9 }, new Date("2026-01-01T10:00:05.000Z"));
    expect(coordinator.roomPlayers(new Date("2026-01-01T10:00:06.000Z"))).toHaveLength(1);

    // Repeated identical beats: membership cached, mirror written once for
    // the presence change and once for the initial status — not per beat.
    expect(effects.listMembershipsCalls).toBe(1);
    const mirrorWrites = effects.mirroredRuntimes.length + effects.mirroredPresence.length;
    await coordinator.guestHeartbeat(guest, { present: true, guestSessionEpoch: 2, presenceSequence: 2 }, new Date("2026-01-01T10:00:15.000Z"));
    expect(effects.mirroredRuntimes.length + effects.mirroredPresence.length).toBe(mirrorWrites);
  });
});
