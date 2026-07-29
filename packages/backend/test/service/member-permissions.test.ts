import { describe, expect, test } from "bun:test";

import { createSqliteRepository } from "../support/sqlite-d1.ts";
import { authVerifier, createBlobSigner, createTestService } from "../support/service-fixtures.ts";

const owner = { playerUuid: "player-owner", playerName: "Owner" };
const guest = { playerUuid: "player-guest", playerName: "Guest" };

async function worldWithGuestMember(repository: ReturnType<typeof createSqliteRepository>, instance: ReturnType<typeof createTestService>) {
  await repository.upsertUser({ playerUuid: owner.playerUuid, playerName: owner.playerName, createdAt: new Date().toISOString() });
  const world = await repository.createWorld(owner, "Friends SMP", "friends-smp");
  const invite = await instance.createInvite(owner, world.id, new Date("2026-01-01T00:00:00.000Z"));
  await instance.redeemInvite(guest, { code: invite.code }, new Date("2026-01-01T00:05:00.000Z"));
  return world;
}

describe("SharedWorldService member command permissions", () => {
  test("owner grants and revokes a member's command permission", async () => {
    const repository = createSqliteRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    const world = await worldWithGuestMember(repository, instance);

    const granted = await instance.setMemberCommandPermission(owner, world.id, guest.playerUuid, { canUseCommands: true });
    expect(granted.canUseCommands).toBe(true);

    const details = await instance.getWorld(owner, world.id, new Date("2026-01-01T01:00:00.000Z"));
    const guestMembership = details.memberships.find((membership) => membership.playerUuid === guest.playerUuid);
    expect(guestMembership?.canUseCommands).toBe(true);

    const revoked = await instance.setMemberCommandPermission(owner, world.id, guest.playerUuid, { canUseCommands: false });
    expect(revoked.canUseCommands).toBe(false);
  });

  test("members cannot change command permissions", async () => {
    const repository = createSqliteRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    const world = await worldWithGuestMember(repository, instance);

    await expect(
      instance.setMemberCommandPermission(guest, world.id, guest.playerUuid, { canUseCommands: true })
    ).rejects.toThrow("owner");
  });

  test("the owner's own permissions cannot be toggled", async () => {
    const repository = createSqliteRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    const world = await worldWithGuestMember(repository, instance);

    await expect(
      instance.setMemberCommandPermission(owner, world.id, owner.playerUuid, { canUseCommands: false })
    ).rejects.toThrow("full command permissions");
  });

  test("toggling an unknown or removed member fails with member_not_found", async () => {
    const repository = createSqliteRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    const world = await worldWithGuestMember(repository, instance);

    await expect(
      instance.setMemberCommandPermission(owner, world.id, "player-stranger", { canUseCommands: true })
    ).rejects.toThrow("member not found");

    await instance.kickMember(owner, world.id, guest.playerUuid, new Date("2026-01-02T00:00:00.000Z"));
    await expect(
      instance.setMemberCommandPermission(owner, world.id, guest.playerUuid, { canUseCommands: true })
    ).rejects.toThrow("member not found");
  });

  test("a kicked member who rejoins does not retain command permission", async () => {
    const repository = createSqliteRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    const world = await worldWithGuestMember(repository, instance);

    await instance.setMemberCommandPermission(owner, world.id, guest.playerUuid, { canUseCommands: true });
    await instance.kickMember(owner, world.id, guest.playerUuid, new Date("2026-01-02T00:00:00.000Z"));

    // Kicking rotates the code, so the rejoin uses the fresh one.
    const freshInvite = await instance.createInvite(owner, world.id, new Date("2026-01-02T00:01:00.000Z"));
    await instance.redeemInvite(guest, { code: freshInvite.code }, new Date("2026-01-02T00:05:00.000Z"));

    const memberships = await repository.listMemberships(world.id);
    const rejoined = memberships.find((membership) => membership.playerUuid === guest.playerUuid);
    expect(rejoined).toBeDefined();
    expect(rejoined?.canUseCommands).toBe(false);
  });

  test("host heartbeat responses carry the membership permission list as a flat superset", async () => {
    const repository = createSqliteRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    const world = await worldWithGuestMember(repository, instance);
    await instance.setMemberCommandPermission(owner, world.id, guest.playerUuid, { canUseCommands: true });

    const entered = await instance.enterSession(owner, world.id, {}, new Date("2026-01-03T00:00:00.000Z"));
    expect(entered.action).toBe("host");

    const heartbeat = await instance.heartbeatHost(
      owner,
      world.id,
      {
        runtimeEpoch: entered.assignment!.runtimeEpoch,
        hostToken: entered.assignment!.hostToken,
        joinTarget: "join.example"
      },
      new Date("2026-01-03T00:00:10.000Z")
    );

    // Runtime-status fields stay at the top level so 0.1.5 clients that bind
    // this body to WorldRuntimeStatus keep working; memberships is a sibling,
    // never a nested wrapper.
    expect(heartbeat.worldId).toBe(world.id);
    expect(heartbeat.phase).toBe("host-live");
    expect(Array.isArray(heartbeat.memberships)).toBe(true);
    const byUuid = new Map(heartbeat.memberships.map((membership) => [membership.playerUuid, membership]));
    expect(byUuid.get(guest.playerUuid)?.canUseCommands).toBe(true);
    expect(byUuid.get(guest.playerUuid)?.playerName).toBe(guest.playerName);
    expect(byUuid.get(owner.playerUuid)?.canUseCommands).toBe(false);

    // The flat-superset contract, pinned structurally: serializing the response
    // must expose runtime fields and memberships at the same JSON depth.
    const serialized = JSON.parse(JSON.stringify(heartbeat)) as Record<string, unknown>;
    expect(Object.keys(serialized)).toContain("phase");
    expect(Object.keys(serialized)).toContain("memberships");
  });
});
