import { describe, expect, test } from "bun:test";

import { createSqliteRepository } from "../support/sqlite-d1.ts";
import { authVerifier, claimHostForTest, createBlobSigner, createTestService } from "../support/service-fixtures.ts";

/**
 * Remote throttle levers: when the SUGGESTED_* env vars are set, responses
 * carry suggested client cadences; when unset, the fields are absent so
 * shipped clients keep their built-in defaults. All fields are additive —
 * old Gson-based mods ignore unknown JSON fields.
 */
describe("server-driven client pacing suggestions", () => {
  const owner = { playerUuid: "player-owner", playerName: "Owner" };

  async function worldWithHost(instance: ReturnType<typeof createTestService>, repository: ReturnType<typeof createSqliteRepository>) {
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld(owner, "Pacing SMP", "pacing-smp");
    await claimHostForTest(instance, owner, world.id);
    return world;
  }

  test("suggestions are absent when the env vars are unset", async () => {
    const repository = createSqliteRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    const world = await worldWithHost(instance, repository);

    const runtime = await instance.runtimeStatus(owner, world.id, new Date("2099-01-01T01:00:00.000Z"));
    expect(runtime.suggestedPollIntervalMs).toBeUndefined();

    const heartbeat = await instance.heartbeatHost(owner, world.id, await hostAuthorization(instance, world.id), new Date("2099-01-01T01:00:00.000Z"));
    expect(heartbeat.suggestedHeartbeatIntervalMs).toBeUndefined();
    expect(heartbeat.suggestedAutosaveIntervalMs).toBeUndefined();

    const presence = await instance.setPlayerPresence(owner, world.id, { present: true, guestSessionEpoch: 1, presenceSequence: 1 }, new Date("2099-01-01T01:00:00.000Z"));
    expect(presence.suggestedIntervalMs).toBeUndefined();
  });

  test("suggestions are emitted when the env vars are set", async () => {
    const repository = createSqliteRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {
      SUGGESTED_RUNTIME_POLL_INTERVAL_MS: "15000",
      SUGGESTED_HOST_HEARTBEAT_INTERVAL_MS: "60000",
      SUGGESTED_AUTOSAVE_INTERVAL_MS: "900000",
      SUGGESTED_PRESENCE_INTERVAL_MS: "30000"
    });
    const world = await worldWithHost(instance, repository);

    const runtime = await instance.runtimeStatus(owner, world.id, new Date("2099-01-01T01:00:00.000Z"));
    expect(runtime.suggestedPollIntervalMs).toBe(15000);

    const heartbeat = await instance.heartbeatHost(owner, world.id, await hostAuthorization(instance, world.id), new Date("2099-01-01T01:00:00.000Z"));
    expect(heartbeat.suggestedHeartbeatIntervalMs).toBe(60000);
    expect(heartbeat.suggestedAutosaveIntervalMs).toBe(900000);
    // The heartbeat body must remain a flat WorldRuntimeStatus superset.
    expect(heartbeat.worldId).toBe(world.id);
    expect(heartbeat.memberships.length).toBeGreaterThan(0);

    const presence = await instance.setPlayerPresence(owner, world.id, { present: true, guestSessionEpoch: 1, presenceSequence: 1 }, new Date("2099-01-01T01:00:00.000Z"));
    expect(presence.suggestedIntervalMs).toBe(30000);
    expect(presence.worldId).toBe(world.id);
  });

  test("garbage env values behave as unset", async () => {
    const repository = createSqliteRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {
      SUGGESTED_RUNTIME_POLL_INTERVAL_MS: "zero",
      SUGGESTED_PRESENCE_INTERVAL_MS: "-5"
    });
    const world = await worldWithHost(instance, repository);

    const runtime = await instance.runtimeStatus(owner, world.id, new Date("2099-01-01T01:00:00.000Z"));
    expect(runtime.suggestedPollIntervalMs).toBeUndefined();
    const presence = await instance.setPlayerPresence(owner, world.id, { present: true, guestSessionEpoch: 1, presenceSequence: 1 }, new Date("2099-01-01T01:00:00.000Z"));
    expect(presence.suggestedIntervalMs).toBeUndefined();
  });
});

async function hostAuthorization(service: { realtimeLocal: { runtimeRecord(worldId: string): { runtimeEpoch: number; runtimeToken: string | null } | null } }, worldId: string) {
  const runtime = service.realtimeLocal.runtimeRecord(worldId);
  return { runtimeEpoch: runtime?.runtimeEpoch, hostToken: runtime?.runtimeToken };
}
