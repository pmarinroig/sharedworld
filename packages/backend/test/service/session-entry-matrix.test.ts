import { describe, expect, test } from "bun:test";

import type { RequestContext } from "../../src/repository.ts";
import type { WorldRuntimeRecord } from "../../src/runtime-protocol.ts";
import { createSqliteRepository } from "../support/sqlite-d1.ts";
import { createBlobSigner, createTestService } from "../support/service-fixtures.ts";

/**
 * Characterization matrix for session entry: every decision cell of
 * enterSession/observeWaiting pinned before the entry-decision restructure.
 * Each test names the runtime/waiter state it drives and asserts the full
 * decision surface (action, assignment, waiterSessionId, runtime view).
 */
describe("session entry decision matrix", () => {
  const OWNER: RequestContext = { playerUuid: "player-owner", playerName: "Owner" };
  const GUEST_1: RequestContext = { playerUuid: "player-guest-1", playerName: "Guest One" };
  const GUEST_2: RequestContext = { playerUuid: "player-guest-2", playerName: "Guest Two" };
  const NOW = new Date("2099-01-01T12:00:00.000Z");
  // Within WAITER_ELECTION_FRESHNESS_MS of NOW: waiters registered at NOW
  // must still be electable when observed at LATER (live clients poll every
  // 1-5s, so a same-poll-cycle observation never sees a stale rival).
  const LATER = new Date("2099-01-01T12:00:15.000Z");

  async function setup() {
    const repository = createSqliteRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, signer, {});
    await repository.upsertUser({ playerUuid: OWNER.playerUuid, playerName: OWNER.playerName, createdAt: NOW.toISOString() });
    const world = await repository.createWorld(OWNER, "Matrix World", "matrix-world");
    for (const [guest, joinedAt] of [
      [GUEST_1, "2026-01-01T00:00:00.000Z"],
      [GUEST_2, "2026-01-02T00:00:00.000Z"]
    ] as const) {
      await repository.addMembership({
        worldId: world.id,
        playerUuid: guest.playerUuid,
        playerName: guest.playerName,
        role: "member",
        joinedAt,
        deletedAt: null,
        canUseCommands: false
      });
    }
    return { repository, instance, worldId: world.id };
  }

  function liveRuntime(worldId: string, overrides: Partial<WorldRuntimeRecord> = {}): WorldRuntimeRecord {
    const issuedAt = NOW.toISOString();
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
      expiresAt: new Date(NOW.getTime() + 60_000).toISOString(),
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

  test("first entrant with no runtime claims host", async () => {
    const { instance, worldId } = await setup();
    const entered = await instance.enterSession(OWNER, worldId, {}, NOW);
    expect(entered.action).toBe("host");
    expect(entered.assignment?.playerUuid).toBe(OWNER.playerUuid);
    expect(entered.assignment?.runtimeEpoch).toBe(1);
    expect(entered.assignment?.hostToken).toBeTruthy();
    expect(entered.runtime.phase).toBe("host-starting");
    expect(entered.runtime.hostUuid).toBe(OWNER.playerUuid);
    expect(entered.waiterSessionId).toBeNull();
  });

  test("guest connects to a live runtime with a join target", async () => {
    const { instance, worldId } = await setup();
    instance.realtimeLocal.seedRuntime(liveRuntime(worldId));
    const entered = await instance.enterSession(GUEST_1, worldId, {}, NOW);
    expect(entered.action).toBe("connect");
    expect(entered.assignment).toBeNull();
    expect(entered.waiterSessionId).toBeNull();
    expect(entered.runtime.phase).toBe("host-live");
    expect(entered.runtime.joinTarget).toBe("example.e4mc.link");
  });

  test("the assigned starting host re-enters and receives the same assignment", async () => {
    const { instance, worldId } = await setup();
    const first = await instance.enterSession(OWNER, worldId, {}, NOW);
    const again = await instance.enterSession(OWNER, worldId, {}, LATER);
    expect(again.action).toBe("host");
    expect(again.assignment?.runtimeEpoch).toBe(first.assignment?.runtimeEpoch ?? -1);
    expect(again.assignment?.hostToken).toBe(first.assignment?.hostToken ?? "");
    expect(again.runtime.phase).toBe("host-starting");
  });

  test("a second player entering while another host is starting waits", async () => {
    const { instance, worldId } = await setup();
    await instance.enterSession(OWNER, worldId, {}, NOW);
    const entered = await instance.enterSession(GUEST_1, worldId, {}, NOW);
    expect(entered.action).toBe("wait");
    expect(entered.assignment).toBeNull();
    expect(entered.waiterSessionId).toBeTruthy();
    expect(entered.runtime.phase).toBe("host-starting");
    expect(entered.runtime.hostUuid).toBe(OWNER.playerUuid);
  });

  test("entry during host-finalizing waits", async () => {
    const { instance, worldId } = await setup();
    instance.realtimeLocal.seedRuntime(liveRuntime(worldId, { phase: "host-finalizing", joinTarget: null, expiresAt: null }));
    const entered = await instance.enterSession(GUEST_1, worldId, {}, NOW);
    expect(entered.action).toBe("wait");
    expect(entered.runtime.phase).toBe("host-finalizing");
    expect(entered.waiterSessionId).toBeTruthy();
  });

  test("a revoked live runtime never direct-connects", async () => {
    const { instance, worldId } = await setup();
    instance.realtimeLocal.seedRuntime(liveRuntime(worldId, { revokedAt: NOW.toISOString() }));
    const entered = await instance.enterSession(GUEST_1, worldId, {}, NOW);
    expect(entered.action).toBe("wait");
    expect(entered.runtime.revokedAt).not.toBeNull();
  });

  test("an expired live runtime warns the next entrant, and acknowledging claims the next epoch", async () => {
    const { instance, worldId } = await setup();
    instance.realtimeLocal.seedRuntime(liveRuntime(worldId, {
      runtimeEpoch: 7,
      expiresAt: new Date(NOW.getTime() - 60_000).toISOString()
    }));
    const warned = await instance.enterSession(OWNER, worldId, {}, NOW);
    expect(warned.action).toBe("warn-host");
    expect(warned.assignment).toBeNull();
    expect(warned.runtime.phase).toBe("idle");
    expect(warned.runtime.uncleanShutdownWarning?.hostUuid).toBe(OWNER.playerUuid);
    expect(warned.runtime.uncleanShutdownWarning?.runtimeEpoch).toBe(7);

    const acknowledged = await instance.enterSession(OWNER, worldId, { acknowledgeUncleanShutdown: true }, LATER);
    expect(acknowledged.action).toBe("host");
    // Epochs never reuse a retired epoch: the warning's epoch is the baseline.
    expect(acknowledged.assignment?.runtimeEpoch).toBe(8);
    expect(acknowledged.runtime.phase).toBe("host-starting");
  });

  test("entering while another player is the preferred idle candidate waits instead of claiming", async () => {
    const { instance, worldId } = await setup();
    instance.realtimeLocal.seedWaiter(worldId, { playerUuid: GUEST_1.playerUuid, playerName: GUEST_1.playerName, waiterSessionId: "wait_g1", waiting: true, updatedAt: NOW.toISOString() });
    const entered = await instance.enterSession(GUEST_2, worldId, {}, NOW);
    expect(entered.action).toBe("wait");
    expect(entered.assignment).toBeNull();
    expect(entered.waiterSessionId).toBeTruthy();
    expect(entered.runtime.phase).toBe("handoff-waiting");
    expect(entered.runtime.candidateUuid).toBe(GUEST_1.playerUuid);
  });

  test("observe without a waiter session id is a 400 invalid_waiter_session", async () => {
    const { instance, worldId } = await setup();
    expect(instance.observeWaiting(GUEST_1, worldId, { waiterSessionId: "  " }, NOW))
      .rejects.toMatchObject({ status: 400, code: "invalid_waiter_session" });
  });

  test("observing while another host is starting keeps waiting", async () => {
    const { instance, worldId } = await setup();
    await instance.enterSession(OWNER, worldId, {}, NOW);
    const entered = await instance.enterSession(GUEST_1, worldId, {}, NOW);
    const observed = await instance.observeWaiting(GUEST_1, worldId, { waiterSessionId: entered.waiterSessionId! }, LATER);
    expect(observed.action).toBe("wait");
    expect(observed.waiterSessionId).toBe(entered.waiterSessionId ?? "");
    expect(observed.runtime.phase).toBe("host-starting");
  });

  test("observing a live runtime connects and cancels the waiter session", async () => {
    const { instance, worldId } = await setup();
    await instance.enterSession(OWNER, worldId, {}, NOW);
    const entered = await instance.enterSession(GUEST_1, worldId, {}, NOW);
    instance.realtimeLocal.seedRuntime(liveRuntime(worldId, { runtimeEpoch: 1, runtimeToken: null }));
    const observed = await instance.observeWaiting(GUEST_1, worldId, { waiterSessionId: entered.waiterSessionId! }, LATER);
    expect(observed.action).toBe("connect");
    expect(observed.waiterSessionId).toBeNull();
    // The waiter session was cancelled server-side (connect is re-derived on
    // every observe, so a follow-up observe still connects).
    const waiters = instance.realtimeLocal.waiters(worldId);
    expect(waiters.filter((waiter) => waiter.waiting)).toHaveLength(0);
    const after = await instance.observeWaiting(GUEST_1, worldId, { waiterSessionId: entered.waiterSessionId! }, LATER);
    expect(after.action).toBe("connect");
  });

  test("observing with a never-registered waiter session restarts", async () => {
    const { instance, worldId } = await setup();
    await instance.enterSession(OWNER, worldId, {}, NOW);
    const observed = await instance.observeWaiting(GUEST_1, worldId, { waiterSessionId: "wait_ghost" }, NOW);
    expect(observed.action).toBe("restart");
    expect(observed.waiterSessionId).toBeNull();
  });

  test("the sole preferred waiter is promoted to host when the runtime is released", async () => {
    const { instance, worldId } = await setup();
    await instance.enterSession(OWNER, worldId, {}, NOW);
    const entered = await instance.enterSession(GUEST_1, worldId, {}, NOW);
    const runtime = instance.realtimeLocal.runtimeRecord(worldId);
    expect(runtime).not.toBeNull();
    instance.realtimeLocal.deleteRuntime(worldId, { runtimeEpoch: runtime!.runtimeEpoch, runtimeToken: runtime!.runtimeToken });
    const observed = await instance.observeWaiting(GUEST_1, worldId, { waiterSessionId: entered.waiterSessionId! }, LATER);
    expect(observed.action).toBe("restart");
    expect(observed.runtime.phase).toBe("host-starting");
    expect(observed.runtime.hostUuid).toBe(GUEST_1.playerUuid);
    expect(observed.runtime.runtimeEpoch).toBe(runtime!.runtimeEpoch + 1);
    expect(observed.waiterSessionId).toBeNull();
  });

  test("an expired live runtime during observe clears waiters and restarts with the warning", async () => {
    const { instance, worldId } = await setup();
    instance.realtimeLocal.seedRuntime(liveRuntime(worldId));
    instance.realtimeLocal.seedWaiter(worldId, { playerUuid: GUEST_1.playerUuid, playerName: GUEST_1.playerName, waiterSessionId: "wait_g1", waiting: true, updatedAt: NOW.toISOString() });
    instance.realtimeLocal.seedRuntime(liveRuntime(worldId, { expiresAt: new Date(LATER.getTime() - 1000).toISOString() }));
    const observed = await instance.observeWaiting(GUEST_1, worldId, { waiterSessionId: "wait_g1" }, LATER);
    expect(observed.action).toBe("restart");
    expect(observed.runtime.phase).toBe("idle");
    expect(observed.runtime.uncleanShutdownWarning?.hostUuid).toBe(OWNER.playerUuid);
  });

  test("observing while someone else is the preferred candidate keeps waiting", async () => {
    const { instance, worldId } = await setup();
    await instance.enterSession(OWNER, worldId, {}, NOW);
    const enteredG1 = await instance.enterSession(GUEST_1, worldId, {}, NOW);
    const enteredG2 = await instance.enterSession(GUEST_2, worldId, {}, NOW);
    const runtime = instance.realtimeLocal.runtimeRecord(worldId);
    instance.realtimeLocal.deleteRuntime(worldId, { runtimeEpoch: runtime!.runtimeEpoch, runtimeToken: runtime!.runtimeToken });
    // GUEST_1 joined the world earlier, so it outranks GUEST_2 as candidate.
    const observed = await instance.observeWaiting(GUEST_2, worldId, { waiterSessionId: enteredG2.waiterSessionId! }, LATER);
    expect(observed.action).toBe("wait");
    expect(observed.waiterSessionId).toBe(enteredG2.waiterSessionId ?? "");
    expect(observed.runtime.candidateUuid).toBe(GUEST_1.playerUuid);
    expect(enteredG1.waiterSessionId).toBeTruthy();
  });
});
