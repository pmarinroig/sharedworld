import { describe, expect, test } from "bun:test";

import type { RequestContext } from "../../src/repository.ts";
import type { WorldRuntimeRecord } from "../../src/runtime-protocol.ts";
import { createSqliteRepository } from "../support/sqlite-d1.ts";

/**
 * Characterization of repository-level runtime and storage-link-session write
 * semantics ahead of the Phase 1 fixes. Tests marked CURRENT-BUG pin behavior
 * that is scheduled to change; each carries the intended post-fix expectation
 * in a comment so the flip is an enumerated edit, not a surprise.
 */
describe("runtime record repository semantics", () => {
  const OWNER: RequestContext = { playerUuid: "player-owner", playerName: "Owner" };
  const NOW = new Date("2099-01-01T12:00:00.000Z");

  async function setupWorld() {
    const repository = createSqliteRepository();
    await repository.upsertUser({ playerUuid: OWNER.playerUuid, playerName: OWNER.playerName, createdAt: NOW.toISOString() });
    const world = await repository.createWorld(OWNER, "Record World", "record-world");
    return { repository, worldId: world.id };
  }

  function runtime(worldId: string, overrides: Partial<WorldRuntimeRecord> = {}): WorldRuntimeRecord {
    const issuedAt = NOW.toISOString();
    return {
      worldId,
      phase: "host-live",
      runtimeEpoch: 5,
      runtimeToken: "token-5",
      hostUuid: OWNER.playerUuid,
      hostPlayerName: OWNER.playerName,
      candidateUuid: null,
      joinTarget: null,
      claimedAt: issuedAt,
      expiresAt: new Date(NOW.getTime() + 120_000).toISOString(),
      startupDeadlineAt: null,
      runtimeTokenIssuedAt: issuedAt,
      lastProgressAt: null,
      updatedAt: issuedAt,
      revokedAt: null,
      startupProgress: null,
      hostMinecraftVersion: null,
      ...overrides
    };
  }

  test("nullable runtime columns round-trip as null, not as the string \"null\"", async () => {
    const { repository, worldId } = await setupWorld();
    await repository.upsertRuntimeRecord(runtime(worldId, {
      runtimeToken: null,
      candidateUuid: null,
      joinTarget: null,
      lastProgressAt: null,
      revokedAt: null,
      hostMinecraftVersion: null
    }));
    const loaded = await repository.getRuntimeRecord(worldId, NOW);
    expect(loaded).not.toBeNull();
    expect(loaded!.runtimeToken).toBeNull();
    expect(loaded!.candidateUuid).toBeNull();
    expect(loaded!.joinTarget).toBeNull();
    expect(loaded!.revokedAt).toBeNull();
    expect(loaded!.hostMinecraftVersion).toBeNull();
    // host_uuid/host_player_name are NOT NULL in the schema, so the mapper's
    // String() coercion cannot observe a NULL today; the Phase 1 change to
    // asNullableString is type-consistency only.
    expect(loaded!.hostUuid).toBe(OWNER.playerUuid);
  });

  test("a fenced delete with the wrong token deletes nothing", async () => {
    const { repository, worldId } = await setupWorld();
    await repository.upsertRuntimeRecord(runtime(worldId));
    const deleted = await repository.deleteRuntimeRecord(worldId, { runtimeEpoch: 5, runtimeToken: "someone-elses-token" });
    expect(deleted).toBe(false);
    expect(await repository.getRuntimeRecord(worldId, NOW)).not.toBeNull();
  });

  test("[S7 fixed] a rejected delete cannot move the high-water mark past the active epoch", async () => {
    const { repository, worldId } = await setupWorld();
    await repository.upsertRuntimeRecord(runtime(worldId));
    const before = await repository.getLastRuntimeEpoch(worldId);
    const deleted = await repository.deleteRuntimeRecord(worldId, { runtimeEpoch: 999, runtimeToken: "bogus" });
    expect(deleted).toBe(false);
    expect(await repository.getLastRuntimeEpoch(worldId)).toBe(before);
    expect(await repository.getRuntimeRecord(worldId, NOW)).not.toBeNull();
    // A superseded host recording its own (strictly lower) epoch as retired is
    // still allowed — that path feeds release-replay detection ([P1]).
    await repository.deleteRuntimeRecord(worldId, { runtimeEpoch: 3, runtimeToken: "old-token" });
    expect(await repository.getLastRuntimeEpoch(worldId)).toBe(3);
  });

  test("a successful fenced delete records the retired epoch", async () => {
    const { repository, worldId } = await setupWorld();
    await repository.upsertRuntimeRecord(runtime(worldId));
    const deleted = await repository.deleteRuntimeRecord(worldId, { runtimeEpoch: 5, runtimeToken: "token-5" });
    expect(deleted).toBe(true);
    expect(await repository.getRuntimeRecord(worldId, NOW)).toBeNull();
    expect(await repository.getLastRuntimeEpoch(worldId)).toBe(5);
  });
});

describe("storage link session update semantics", () => {
  const NOW = new Date("2099-01-01T12:00:00.000Z");

  async function setupSession() {
    const repository = createSqliteRepository();
    await repository.createStorageLinkSession({
      id: "sess-1",
      playerUuid: "player-owner",
      provider: "google-drive",
      status: "pending",
      authUrl: "https://accounts.google.com/o/oauth2/auth?x=1",
      state: "sess-1:nonce",
      linkedAccountEmail: null,
      accountDisplayName: null,
      storageAccountId: null,
      errorMessage: null,
      createdAt: NOW.toISOString(),
      expiresAt: new Date(NOW.getTime() + 600_000).toISOString(),
      completedAt: null
    });
    return repository;
  }

  test("updates overwrite provided fields", async () => {
    const repository = await setupSession();
    await repository.updateStorageLinkSession("sess-1", { status: "failed", errorMessage: "boom" });
    const session = await repository.getStorageLinkSession("sess-1");
    expect(session?.status).toBe("failed");
    expect(session?.errorMessage).toBe("boom");
  });

  test("[S2 fixed] errorMessage null clears a previous failure message", async () => {
    const repository = await setupSession();
    await repository.updateStorageLinkSession("sess-1", { status: "failed", errorMessage: "boom" });
    await repository.updateStorageLinkSession("sess-1", {
      status: "linked",
      linkedAccountEmail: "kid@example.com",
      errorMessage: null,
      completedAt: NOW.toISOString()
    });
    const session = await repository.getStorageLinkSession("sess-1");
    expect(session?.status).toBe("linked");
    expect(session?.errorMessage).toBeNull();
    expect(session?.linkedAccountEmail).toBe("kid@example.com");
  });

  test("omitted fields keep their current values", async () => {
    const repository = await setupSession();
    await repository.updateStorageLinkSession("sess-1", { status: "failed", errorMessage: "boom" });
    await repository.updateStorageLinkSession("sess-1", { status: "pending" });
    const session = await repository.getStorageLinkSession("sess-1");
    expect(session?.status).toBe("pending");
    // errorMessage was not present in the update, so it is preserved.
    expect(session?.errorMessage).toBe("boom");
  });
});
