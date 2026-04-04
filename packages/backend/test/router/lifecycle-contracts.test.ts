import { describe, expect, test } from "bun:test";

import {
  authedRequest,
  createWorldResultFixture,
  enterSessionResponseFixture,
  lifecycleRouter,
  observeWaitingResponseFixture,
  runtimeStatusFixture
} from "../support/router.ts";

describe("router lifecycle contracts", () => {
  test("create world and waiting routes preserve runtime/session wire shape", async () => {
    const calls: Array<{ kind: string; worldId?: string; body?: unknown }> = [];
    const router = lifecycleRouter({
      async createWorld(_ctx, request) {
        calls.push({ kind: "createWorld", body: request });
        return createWorldResultFixture({
          world: { id: "world-1" },
          initialUploadAssignment: { worldId: "world-1", runtimeEpoch: 7, hostToken: "token-7", playerUuid: "player-owner" }
        });
      },
      async enterSession(_ctx, worldId, request) {
        calls.push({ kind: "enterSession", worldId, body: request });
        return enterSessionResponseFixture({
          action: "warn-host",
          world: { id: worldId },
          runtime: {
            worldId,
            uncleanShutdownWarning: {
              hostUuid: "player-host",
              hostPlayerName: "Host",
              phase: "host-finalizing",
              runtimeEpoch: 6,
              recordedAt: "2099-01-01T00:00:00.000Z"
            }
          }
        });
      },
      async observeWaiting(_ctx, worldId, request) {
        calls.push({ kind: "observeWaiting", worldId, body: request });
        return observeWaitingResponseFixture({
          action: "restart",
          runtime: { worldId }
        });
      },
      async cancelWaiting(_ctx, worldId, request) {
        calls.push({ kind: "cancelWaiting", worldId, body: request });
        return runtimeStatusFixture({ worldId });
      },
      async runtimeStatus(_ctx, worldId) {
        calls.push({ kind: "runtime", worldId });
        return runtimeStatusFixture({ worldId });
      }
    });

    const createResponse = await router(authedRequest("/worlds", "POST", {
      name: "World",
      motdLine1: "MOTD",
      importSource: { type: "local-save", id: "save-1", name: "Save 1" },
      storageLinkSessionId: ""
    }));
    const enterResponse = await router(authedRequest("/worlds/world-1/session/enter", "POST", { waiterSessionId: null, acknowledgeUncleanShutdown: true }));
    const observeResponse = await router(authedRequest("/worlds/world-1/session/waiting/observe", "POST", { waiterSessionId: "wait-1" }));
    const cancelResponse = await router(authedRequest("/worlds/world-1/session/waiting/cancel", "POST", { waiterSessionId: "wait-1" }));
    const runtimeResponse = await router(authedRequest("/worlds/world-1/runtime"));

    await expect(createResponse.json()).resolves.toMatchObject({
      world: { id: "world-1" },
      initialUploadAssignment: { runtimeEpoch: 7, hostToken: "token-7" }
    });
    await expect(enterResponse.json()).resolves.toMatchObject({
      action: "warn-host",
      runtime: {
        uncleanShutdownWarning: {
          hostUuid: "player-host",
          hostPlayerName: "Host",
          phase: "host-finalizing",
          runtimeEpoch: 6
        }
      }
    });
    await expect(observeResponse.json()).resolves.toMatchObject({ action: "restart" });
    await expect(cancelResponse.json()).resolves.toMatchObject({ worldId: "world-1", phase: "idle" });
    await expect(runtimeResponse.json()).resolves.toMatchObject({ worldId: "world-1", phase: "idle" });
    expect(calls).toEqual([
      { kind: "createWorld", body: { name: "World", motdLine1: "MOTD", importSource: { type: "local-save", id: "save-1", name: "Save 1" }, storageLinkSessionId: "" } },
      { kind: "enterSession", worldId: "world-1", body: { waiterSessionId: null, acknowledgeUncleanShutdown: true } },
      { kind: "observeWaiting", worldId: "world-1", body: { waiterSessionId: "wait-1" } },
      { kind: "cancelWaiting", worldId: "world-1", body: { waiterSessionId: "wait-1" } },
      { kind: "runtime", worldId: "world-1" }
    ]);
  });

  test("host lifecycle routes forward explicit runtime authorization", async () => {
    const calls: Array<{ kind: string; worldId: string; body: unknown }> = [];
    const router = lifecycleRouter({
      async heartbeatHost(_ctx, worldId, request) {
        calls.push({ kind: "heartbeat", worldId, body: request });
        return runtimeStatusFixture({ worldId, phase: "host-live", runtimeEpoch: 7, joinTarget: "join.example" });
      },
      async setHostStartupProgress(_ctx, worldId, request) {
        calls.push({ kind: "progress", worldId, body: request });
        return runtimeStatusFixture({
          worldId,
          phase: "host-live",
          runtimeEpoch: 7,
          startupProgress: {
            label: "Syncing",
            mode: "determinate",
            fraction: 0.4,
            updatedAt: "2099-01-01T00:00:00.000Z"
          }
        });
      },
      async beginFinalization(_ctx, worldId, request) {
        calls.push({ kind: "begin", worldId, body: request });
        return { worldId, nextHostUuid: null, nextHostPlayerName: null, status: "finalizing" };
      },
      async completeFinalization(_ctx, worldId, request) {
        calls.push({ kind: "complete", worldId, body: request });
        return { worldId, nextHostUuid: "player-next", nextHostPlayerName: "Next Host", status: "handoff" };
      },
      async releaseHost(_ctx, worldId, request) {
        calls.push({ kind: "release", worldId, body: request });
        return {
          worldId,
          releasedAt: "2099-01-01T00:00:00.000Z",
          graceful: false,
          nextHostUuid: null,
          nextHostPlayerName: null
        };
      }
    });

    const auth = { runtimeEpoch: 7, hostToken: "token-7" };
    await router(authedRequest("/worlds/world-1/heartbeat", "POST", { ...auth, joinTarget: "join.example" }));
    await router(authedRequest("/worlds/world-1/host-startup-progress", "POST", { ...auth, label: "Syncing", mode: "determinate", fraction: 0.4 }));
    await router(authedRequest("/worlds/world-1/begin-finalization", "POST", auth));
    const completeResponse = await router(authedRequest("/worlds/world-1/complete-finalization", "POST", auth));
    const releaseResponse = await router(authedRequest("/worlds/world-1/release-host", "POST", { ...auth, graceful: false }));

    await expect(completeResponse.json()).resolves.toMatchObject({ status: "handoff", nextHostUuid: "player-next" });
    await expect(releaseResponse.json()).resolves.toMatchObject({ graceful: false, nextHostUuid: null });
    expect(calls).toEqual([
      { kind: "heartbeat", worldId: "world-1", body: { runtimeEpoch: 7, hostToken: "token-7", joinTarget: "join.example" } },
      { kind: "progress", worldId: "world-1", body: { runtimeEpoch: 7, hostToken: "token-7", label: "Syncing", mode: "determinate", fraction: 0.4 } },
      { kind: "begin", worldId: "world-1", body: { runtimeEpoch: 7, hostToken: "token-7" } },
      { kind: "complete", worldId: "world-1", body: { runtimeEpoch: 7, hostToken: "token-7" } },
      { kind: "release", worldId: "world-1", body: { runtimeEpoch: 7, hostToken: "token-7", graceful: false } }
    ]);
  });

  test("legacy lifecycle routes are not exposed anymore", async () => {
    const router = lifecycleRouter();

    for (const path of [
      "/worlds/world-1/claim-host",
      "/worlds/world-1/active-host",
      "/worlds/world-1/handoff-ready",
      "/worlds/world-1/join-resolution"
    ]) {
      const response = await router(authedRequest(path, "POST", {}));
      expect(response.status).toBe(404);
    }
  });
});
