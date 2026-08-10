import { describe, expect, test } from "bun:test";

import { createSqliteRepository } from "../support/sqlite-d1.ts";

/**
 * EB1 read-path foundations: sessionActorFacts folds the hasActiveWorld +
 * isWorldMember + hasWorldMembership triple into one query, world summaries
 * build set-based, and getActiveInvite is a pure read (expiry enforced in
 * the WHERE clause, physically applied on the next invite write).
 */
describe("D1 repository read paths", () => {
  const owner = { playerUuid: "player-owner", playerName: "Owner" };

  async function seedWorld(repository: ReturnType<typeof createSqliteRepository>) {
    await repository.upsertUser({ playerUuid: owner.playerUuid, playerName: owner.playerName, createdAt: new Date().toISOString() });
    return repository.createWorld(owner, "Friends SMP", "friends-smp");
  }

  describe("sessionActorFacts", () => {
    test("covers the full membership truth table in one query", async () => {
      const repository = createSqliteRepository();
      const world = await seedWorld(repository);
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

      expect(await repository.sessionActorFacts(world.id, owner.playerUuid))
        .toEqual({ membershipActive: true, everMember: true });
      expect(await repository.sessionActorFacts(world.id, "player-guest"))
        .toEqual({ membershipActive: false, everMember: true });
      expect(await repository.sessionActorFacts(world.id, "player-outsider"))
        .toEqual({ membershipActive: false, everMember: false });
      expect(await repository.sessionActorFacts("world_missing", owner.playerUuid)).toBeNull();
    });

    test("a deleted world reads as missing", async () => {
      const repository = createSqliteRepository();
      const world = await seedWorld(repository);
      await repository.deleteWorldForPlayer(owner, world.id, new Date());
      expect(await repository.sessionActorFacts(world.id, owner.playerUuid)).toBeNull();
    });
  });

  describe("buildWorldSummaries batch", () => {
    test("list and details agree with per-world facts across multiple worlds", async () => {
      const repository = createSqliteRepository();
      await repository.upsertUser({ playerUuid: owner.playerUuid, playerName: owner.playerName, createdAt: new Date().toISOString() });
      const first = await repository.createWorld(owner, "Alpha", "alpha");
      const second = await repository.createWorld(owner, "Beta", "beta");
      await repository.addMembership({
        worldId: second.id,
        playerUuid: "player-guest",
        playerName: "Guest",
        role: "member",
        joinedAt: new Date().toISOString(),
        deletedAt: null,
        canUseCommands: false
      });

      const worlds = await repository.listWorldsForPlayer(owner.playerUuid);
      expect(worlds.map((world) => world.name)).toEqual(["Alpha", "Beta"]);
      const alpha = worlds.find((world) => world.id === first.id);
      const beta = worlds.find((world) => world.id === second.id);
      expect(alpha?.memberCount).toBe(1);
      expect(beta?.memberCount).toBe(2);

      const details = await repository.getWorldDetails(second.id, "player-guest");
      expect(details?.memberCount).toBe(2);
      expect(details?.membership.playerUuid).toBe("player-guest");
      expect(details?.memberships).toHaveLength(2);
    });

    test("a deleted world drops out of the list instead of throwing", async () => {
      const repository = createSqliteRepository();
      await repository.upsertUser({ playerUuid: owner.playerUuid, playerName: owner.playerName, createdAt: new Date().toISOString() });
      const first = await repository.createWorld(owner, "Alpha", "alpha");
      const second = await repository.createWorld(owner, "Beta", "beta");
      await repository.deleteWorldForPlayer(owner, first.id, new Date());

      const worlds = await repository.listWorldsForPlayer(owner.playerUuid);
      expect(worlds.map((world) => world.id)).toEqual([second.id]);
      expect(await repository.getWorldDetails(first.id, owner.playerUuid)).toBeNull();
    });
  });

  describe("getActiveInvite", () => {
    const invite = (worldId: string, id: string, createdAt: string, expiresAt: string) => ({
      id,
      worldId,
      code: `code-${id}`,
      createdByUuid: owner.playerUuid,
      createdAt,
      expiresAt,
      status: "active" as const
    });

    test("an expired invite is invisible without any write", async () => {
      const repository = createSqliteRepository();
      const world = await seedWorld(repository);
      await repository.createInvite(world.id, owner, invite(world.id, "inv-1", "2026-01-01T00:00:00.000Z", "2026-01-02T00:00:00.000Z"));

      expect(await repository.getActiveInvite(world.id, new Date("2026-01-01T12:00:00.000Z"))).not.toBeNull();
      expect(await repository.getActiveInvite(world.id, new Date("2026-01-03T00:00:00.000Z"))).toBeNull();
      // The row itself was not touched by the reads: it still reads as
      // active before its expiry moment.
      expect(await repository.getActiveInvite(world.id, new Date("2026-01-01T12:00:00.000Z"))).not.toBeNull();
    });

    test("creating a new invite physically expires stale predecessors", async () => {
      const repository = createSqliteRepository();
      const world = await seedWorld(repository);
      await repository.createInvite(world.id, owner, invite(world.id, "inv-1", "2026-01-01T00:00:00.000Z", "2026-01-02T00:00:00.000Z"));
      await repository.createInvite(world.id, owner, invite(world.id, "inv-2", "2026-02-01T00:00:00.000Z", "2026-02-02T00:00:00.000Z"));

      const active = await repository.getActiveInvite(world.id, new Date("2026-02-01T12:00:00.000Z"));
      expect(active?.id).toBe("inv-2");
      const stale = await repository.getInviteByCode("code-inv-1");
      expect(stale?.status).toBe("expired");
    });
  });
});
