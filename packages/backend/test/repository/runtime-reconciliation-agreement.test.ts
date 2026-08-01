import { describe, expect, test } from "bun:test";

import type { RequestContext } from "../../src/repository.ts";
import type { WorldRuntimeRecord } from "../../src/runtime-protocol.ts";
import { runtimePhaseToWorldStatus } from "../../src/runtime-protocol.ts";
import { createSqliteRepository } from "../support/sqlite-d1.ts";
import { authVerifier, createBlobSigner, createTestService } from "../support/service-fixtures.ts";

/**
 * Runtime reconciliation exists twice: the service path
 * (runtime-access.ts resolveRuntimeState, consumed via runtimeStatus) and the
 * repository's private summary path (getDisplayRuntimeRecord /
 * preferredWaiterCandidate, consumed via listWorldsForPlayer). This suite pins
 * that both views agree on the world lifecycle for every runtime state, so the
 * planned unification (single TS reconciliation) is provably behavior-preserving.
 *
 * The summary path reads the wall clock internally, so fixtures place deadlines
 * relative to the real current time.
 */
describe("runtime reconciliation agreement: summaries vs runtime status", () => {
  const OWNER: RequestContext = { playerUuid: "player-owner", playerName: "Owner" };
  const GUEST: RequestContext = { playerUuid: "player-guest", playerName: "Guest" };

  async function setup() {
    const repository = createSqliteRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    await repository.upsertUser({ playerUuid: OWNER.playerUuid, playerName: OWNER.playerName, createdAt: new Date().toISOString() });
    const world = await repository.createWorld(OWNER, "Agreement World", "agreement-world");
    await repository.addMembership({
      worldId: world.id,
      playerUuid: GUEST.playerUuid,
      playerName: GUEST.playerName,
      role: "member",
      joinedAt: "2026-01-01T00:00:00.000Z",
      deletedAt: null,
      canUseCommands: false
    });
    return { repository, instance, worldId: world.id };
  }

  function runtime(worldId: string, overrides: Partial<WorldRuntimeRecord> = {}): WorldRuntimeRecord {
    const now = Date.now();
    const issuedAt = new Date(now).toISOString();
    return {
      worldId,
      phase: "host-live",
      runtimeEpoch: 1,
      runtimeToken: "token-1",
      hostUuid: OWNER.playerUuid,
      hostPlayerName: OWNER.playerName,
      candidateUuid: null,
      joinTarget: "example.e4mc.link",
      claimedAt: issuedAt,
      expiresAt: new Date(now + 120_000).toISOString(),
      startupDeadlineAt: null,
      runtimeTokenIssuedAt: issuedAt,
      lastProgressAt: issuedAt,
      updatedAt: issuedAt,
      revokedAt: null,
      startupProgress: null,
      hostMinecraftVersion: null,
      ...overrides
    };
  }

  async function summaryFor(repository: Awaited<ReturnType<typeof setup>>["repository"], worldId: string) {
    const summaries = await repository.listWorldsForPlayer(OWNER.playerUuid);
    const summary = summaries.find((entry) => entry.id === worldId);
    expect(summary).toBeDefined();
    return summary!;
  }

  test("live runtime: summary says hosting, status says host-live", async () => {
    const { repository, instance, worldId } = await setup();
    await repository.upsertRuntimeRecord(runtime(worldId));
    const summary = await summaryFor(repository, worldId);
    const status = await instance.runtimeStatus(OWNER, worldId, new Date());
    expect(summary.status).toBe("hosting");
    expect(runtimePhaseToWorldStatus(status.phase)).toBe(summary.status);
    expect(summary.activeHostUuid).toBe(status.hostUuid ?? "");
  });

  test("finalizing runtime: both sides report finalizing", async () => {
    const { repository, instance, worldId } = await setup();
    await repository.upsertRuntimeRecord(runtime(worldId, { phase: "host-finalizing", joinTarget: null, expiresAt: null }));
    const summary = await summaryFor(repository, worldId);
    const status = await instance.runtimeStatus(OWNER, worldId, new Date());
    expect(summary.status).toBe("finalizing");
    expect(runtimePhaseToWorldStatus(status.phase)).toBe(summary.status);
  });

  test("expired live runtime: both sides retire it and surface idle", async () => {
    const { repository, instance, worldId } = await setup();
    await repository.upsertRuntimeRecord(runtime(worldId, {
      expiresAt: new Date(Date.now() - 60_000).toISOString(),
      lastProgressAt: new Date(Date.now() - 60_000).toISOString(),
      updatedAt: new Date(Date.now() - 60_000).toISOString()
    }));
    const summary = await summaryFor(repository, worldId);
    const status = await instance.runtimeStatus(OWNER, worldId, new Date());
    expect(summary.status).toBe("idle");
    expect(status.phase).toBe("idle");
    expect(status.uncleanShutdownWarning).not.toBeNull();
  });

  test("expired starting runtime (plain timeout): both sides surface the same lifecycle", async () => {
    const { repository, instance, worldId } = await setup();
    await repository.upsertRuntimeRecord(runtime(worldId, {
      phase: "host-starting",
      joinTarget: null,
      startupDeadlineAt: new Date(Date.now() - 60_000).toISOString(),
      expiresAt: new Date(Date.now() - 60_000).toISOString()
    }));
    // A waiter left behind by the dead startup attempt:
    await repository.upsertWaiterSession(worldId, GUEST, "wait_guest", new Date());

    const summary = await summaryFor(repository, worldId);
    const status = await instance.runtimeStatus(OWNER, worldId, new Date());
    // Neither reconciliation clears waiters on a plain (non-warning) timeout,
    // so both sides elect the surviving waiter as the handoff candidate.
    expect(summary.status).toBe("handoff");
    expect(runtimePhaseToWorldStatus(status.phase)).toBe(summary.status);
    expect(status.candidateUuid).toBe(GUEST.playerUuid);
  });

  test("idle world with an active waiter: both sides report handoff toward the same candidate", async () => {
    const { repository, instance, worldId } = await setup();
    await repository.upsertWaiterSession(worldId, GUEST, "wait_guest", new Date());
    const summary = await summaryFor(repository, worldId);
    const status = await instance.runtimeStatus(OWNER, worldId, new Date());
    expect(summary.status).toBe("handoff");
    expect(status.phase).toBe("handoff-waiting");
    expect(status.candidateUuid).toBe(GUEST.playerUuid);
  });

  test("idle world, no runtime, no waiters: both idle", async () => {
    const { repository, instance, worldId } = await setup();
    const summary = await summaryFor(repository, worldId);
    const status = await instance.runtimeStatus(OWNER, worldId, new Date());
    expect(summary.status).toBe("idle");
    expect(status.phase).toBe("idle");
  });
});
