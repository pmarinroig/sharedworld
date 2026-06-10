import { describe, expect, test } from "bun:test";

import type { WorldRuntimeRecord } from "../../src/runtime-protocol.ts";
import { createSqliteRepository } from "../support/sqlite-d1.ts";

type Row = Record<string, unknown>;

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
});
