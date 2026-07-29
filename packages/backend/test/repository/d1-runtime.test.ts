import { describe, expect, test } from "bun:test";

import type { WorldRuntimeRecord } from "../../src/runtime-protocol.ts";
import { createSqliteRepository } from "../support/sqlite-d1.ts";

describe("D1SharedWorldRepository", () => {
  test("runtime protocol fields round-trip through getRuntimeRecord", async () => {
    const repository = createSqliteRepository();
    const world = await repository.createWorld(
      { playerUuid: "player-host", playerName: "Host" },
      "Round Trip",
      "round-trip"
    );
    const runtime: WorldRuntimeRecord = {
      worldId: world.id,
      phase: "host-starting",
      runtimeEpoch: 7,
      runtimeToken: "rt_token_7",
      hostUuid: "player-host",
      hostPlayerName: "Host",
      candidateUuid: null,
      joinTarget: null,
      claimedAt: "2099-01-03T00:00:00.000Z",
      expiresAt: "2099-01-03T00:05:00.000Z",
      startupDeadlineAt: "2099-01-03T00:01:30.000Z",
      runtimeTokenIssuedAt: "2099-01-03T00:00:00.000Z",
      lastProgressAt: "2099-01-03T00:00:10.000Z",
      updatedAt: "2099-01-03T00:00:10.000Z",
      revokedAt: null,
    hostMinecraftVersion: null,
      startupProgress: {
        label: "Preparing world",
        mode: "indeterminate",
        fraction: null,
        updatedAt: "2099-01-03T00:00:10.000Z"
      }
    };

    await repository.upsertRuntimeRecord(runtime);

    const loaded = await repository.getRuntimeRecord(world.id, new Date("2099-01-03T00:00:20.000Z"));

    expect(loaded).not.toBeNull();
    expect(loaded).toEqual(runtime);
  });

  test("[P1] two racing acquires for the same epoch install exactly one runtime", async () => {
    const repository = createSqliteRepository();
    const world = await repository.createWorld(
      { playerUuid: "player-host", playerName: "Host" },
      "Acquire Race",
      "acquire-race"
    );
    const base = runtimeFixture(world.id, 1, "rt_first");

    expect(await repository.claimRuntimeAssignment(base)).toBe(true);
    // The concurrent loser computed the same next epoch with its own token.
    expect(await repository.claimRuntimeAssignment({ ...base, runtimeToken: "rt_second", hostUuid: "player-guest", hostPlayerName: "Guest" })).toBe(false);

    const stored = await repository.getRuntimeRecord(world.id, new Date("2099-01-03T00:00:01.000Z"));
    expect(stored?.runtimeToken).toBe("rt_first");
    expect(stored?.hostUuid).toBe("player-host");

    // A genuinely newer epoch still replaces the row.
    expect(await repository.claimRuntimeAssignment(runtimeFixture(world.id, 2, "rt_next"))).toBe(true);
  });

  test("[P1] a stale refresh write cannot resurrect a replaced runtime", async () => {
    const repository = createSqliteRepository();
    const world = await repository.createWorld(
      { playerUuid: "player-host", playerName: "Host" },
      "Stale Refresh",
      "stale-refresh"
    );
    const staleRuntime = runtimeFixture(world.id, 1, "rt_old");
    await repository.upsertRuntimeRecord(staleRuntime);
    // The stale host read epoch 1, then the runtime moved on to epoch 2.
    const currentRuntime = runtimeFixture(world.id, 2, "rt_new");
    await repository.upsertRuntimeRecord(currentRuntime);

    expect(await repository.updateAuthorizedRuntime({ ...staleRuntime, joinTarget: "stale.example:25565" })).toBe(false);

    const stored = await repository.getRuntimeRecord(world.id, new Date("2099-01-03T00:00:01.000Z"));
    expect(stored?.runtimeEpoch).toBe(2);
    expect(stored?.runtimeToken).toBe("rt_new");
    expect(stored?.joinTarget).toBeNull();

    expect(await repository.updateAuthorizedRuntime({ ...currentRuntime, joinTarget: "live.example:25565" })).toBe(true);
    const refreshed = await repository.getRuntimeRecord(world.id, new Date("2099-01-03T00:00:02.000Z"));
    expect(refreshed?.joinTarget).toBe("live.example:25565");
  });

  test("[P1] a stale delete leaves a newer runtime untouched but still retires its own epoch", async () => {
    const repository = createSqliteRepository();
    const world = await repository.createWorld(
      { playerUuid: "player-host", playerName: "Host" },
      "Stale Delete",
      "stale-delete"
    );
    await repository.upsertRuntimeRecord(runtimeFixture(world.id, 2, "rt_new"));

    expect(await repository.deleteRuntimeRecord(world.id, { runtimeEpoch: 1, runtimeToken: "rt_old" })).toBe(false);
    expect(await repository.getRuntimeRecord(world.id, new Date("2099-01-03T00:00:01.000Z"))).not.toBeNull();
    expect(await repository.getLastRuntimeEpoch(world.id)).toBe(1);

    expect(await repository.deleteRuntimeRecord(world.id, { runtimeEpoch: 2, runtimeToken: "rt_new" })).toBe(true);
    expect(await repository.getRuntimeRecord(world.id, new Date("2099-01-03T00:00:02.000Z"))).toBeNull();
    expect(await repository.getLastRuntimeEpoch(world.id)).toBe(2);
  });

  test("[I3] leaving a world while hosting keeps the epoch high-water mark", async () => {
    const repository = createSqliteRepository();
    const world = await repository.createWorld(
      { playerUuid: "player-owner", playerName: "Owner" },
      "Leave While Hosting",
      "leave-while-hosting"
    );
    await repository.addMembership({
      worldId: world.id,
      playerUuid: "player-host",
      playerName: "Host",
      role: "member",
      joinedAt: "2099-01-01T00:00:00.000Z",
      deletedAt: null,
      canUseCommands: false
    });
    await repository.upsertRuntimeRecord(runtimeFixture(world.id, 5, "rt_hosting"));

    await repository.deleteWorldForPlayer({ playerUuid: "player-host", playerName: "Host" }, world.id, new Date("2099-01-02T00:00:00.000Z"));

    expect(await repository.getRuntimeRecord(world.id, new Date("2099-01-02T00:00:01.000Z"))).toBeNull();
    expect(await repository.getLastRuntimeEpoch(world.id)).toBe(5);
  });
});

function runtimeFixture(worldId: string, runtimeEpoch: number, runtimeToken: string): WorldRuntimeRecord {
  return {
    worldId,
    phase: "host-starting",
    runtimeEpoch,
    runtimeToken,
    hostUuid: "player-host",
    hostPlayerName: "Host",
    candidateUuid: null,
    joinTarget: null,
    claimedAt: "2099-01-03T00:00:00.000Z",
    expiresAt: "2099-01-03T00:05:00.000Z",
    startupDeadlineAt: "2099-01-03T00:05:00.000Z",
    runtimeTokenIssuedAt: "2099-01-03T00:00:00.000Z",
    lastProgressAt: null,
    updatedAt: "2099-01-03T00:00:00.000Z",
    revokedAt: null,
    hostMinecraftVersion: null,
    startupProgress: null
  };
}
