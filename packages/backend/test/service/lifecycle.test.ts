import { describe, expect, test } from "bun:test";

import { MemorySharedWorldRepository } from "../../src/memory-repository.ts";
import { authVerifier, createBlobSigner, createTestService } from "../support/service-fixtures.ts";

describe("SharedWorldService lifecycle", () => {
  test("create world returns a dedicated initial upload assignment without waiter state", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });

    const created = await instance.createWorld(
      { playerUuid: "player-owner", playerName: "Owner" },
      {
        name: "World",
        motdLine1: "MOTD",
        importSource: { type: "local-save", id: "save-1", name: "Save 1" },
        storageLinkSessionId: ""
      },
      new Date("2099-01-01T00:00:00.000Z")
    );

    expect(created.initialUploadAssignment.playerUuid).toBe("player-owner");
    expect(created.initialUploadAssignment.runtimeEpoch).toBe(1);
    expect(await repository.listActiveWaiters(created.world.id, new Date("2099-01-01T00:00:00.000Z"))).toEqual([]);
    expect(await repository.getRuntimeRecord(created.world.id, new Date("2099-01-01T00:00:00.000Z"))).not.toBeNull();
  });

  test("observe waiting restarts when the current waiter is promoted to host", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    await repository.upsertUser({ playerUuid: "player-member", playerName: "Member", createdAt: new Date().toISOString() });
    const world = await repository.createWorld({ playerUuid: "player-owner", playerName: "Owner" }, "World", "world");
    await repository.addMembership({
      worldId: world.id,
      playerUuid: "player-member",
      playerName: "Member",
      role: "member",
      joinedAt: "2099-01-01T00:00:00.000Z",
      deletedAt: null
    });
    await repository.upsertWaiterSession(world.id, { playerUuid: "player-member", playerName: "Member" }, "wait-member", new Date("2099-01-01T00:00:00.000Z"));

    const observed = await instance.observeWaiting(
      { playerUuid: "player-member", playerName: "Member" },
      world.id,
      { waiterSessionId: "wait-member" },
      new Date("2099-01-01T00:00:01.000Z")
    );

    expect(observed.action).toBe("restart");
    expect(observed.assignment).toBeNull();

    const entered = await instance.enterSession(
      { playerUuid: "player-member", playerName: "Member" },
      world.id,
      {},
      new Date("2099-01-01T00:00:02.000Z")
    );

    expect(entered.action).toBe("host");
    expect(entered.assignment?.playerUuid).toBe("player-member");
  });

  test("observe waiting restarts when the waiter session no longer exists", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    await repository.upsertUser({ playerUuid: "player-member", playerName: "Member", createdAt: new Date().toISOString() });
    const world = await repository.createWorld({ playerUuid: "player-owner", playerName: "Owner" }, "World", "world");
    await repository.addMembership({
      worldId: world.id,
      playerUuid: "player-member",
      playerName: "Member",
      role: "member",
      joinedAt: "2099-01-01T00:00:00.000Z",
      deletedAt: null
    });

    const observed = await instance.observeWaiting(
      { playerUuid: "player-member", playerName: "Member" },
      world.id,
      { waiterSessionId: "missing-session" },
      new Date("2099-01-01T00:00:01.000Z")
    );

    expect(observed.action).toBe("restart");
  });

  test("begin finalization clears the join target and forces join resolution to wait", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld({ playerUuid: "player-owner", playerName: "Owner" }, "Friends SMP", "friends-smp");
    await repository.addMembership({
      worldId: world.id,
      playerUuid: "player-guest",
      playerName: "Guest",
      role: "member",
      joinedAt: "2026-01-02T00:00:00.000Z",
      deletedAt: null
    });

    await instance.claimHost(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      { joinTarget: "example.test:25565" },
      new Date("2099-01-03T00:00:00.000Z")
    );

    const finalizing = await instance.beginFinalization(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {},
      new Date("2099-01-03T00:01:00.000Z")
    );
    const hostStatus = await instance.activeHost({ playerUuid: "player-guest", playerName: "Guest" }, world.id);
    const joinResolution = await instance.resolveJoin({ playerUuid: "player-guest", playerName: "Guest" }, world.id);

    expect(finalizing.status).toBe("finalizing");
    expect(hostStatus.activeLease?.status).toBe("finalizing");
    expect(hostStatus.activeLease?.joinTarget).toBeNull();
    expect(joinResolution.action).toBe("wait");
  });

  test("runtime status never exposes a join target during finalization", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld({ playerUuid: "player-owner", playerName: "Owner" }, "Friends SMP", "friends-smp");
    await repository.addMembership({
      worldId: world.id,
      playerUuid: "player-guest",
      playerName: "Guest",
      role: "member",
      joinedAt: "2026-01-02T00:00:00.000Z",
      deletedAt: null
    });

    await instance.claimHost(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      { joinTarget: "example.test:25565" },
      new Date("2099-01-03T00:00:00.000Z")
    );
    const runtimeBeforeFinalization = await repository.getRuntimeRecord(world.id, new Date("2099-01-03T00:00:00.000Z"));
    expect(runtimeBeforeFinalization?.joinTarget).toBe("example.test:25565");

    await instance.beginFinalization(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {},
      new Date("2099-01-03T00:01:00.000Z")
    );

    const runtime = await instance.runtimeStatus(
      { playerUuid: "player-guest", playerName: "Guest" },
      world.id,
      new Date("2099-01-03T00:01:05.000Z")
    );

    expect(runtime.phase).toBe("host-finalizing");
    expect(runtime.joinTarget).toBeNull();
  });

  test("host-starting heartbeat extends the startup deadline on the real enter-session path", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld({ playerUuid: "player-owner", playerName: "Owner" }, "Friends SMP", "friends-smp");

    const entered = await instance.enterSession(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {},
      new Date("2099-01-03T00:00:00.000Z")
    );

    expect(entered.action).toBe("host");
    expect(entered.assignment).not.toBeNull();
    const initialRuntime = await repository.getRuntimeRecord(world.id, new Date("2099-01-03T00:00:00.000Z"));
    expect(initialRuntime?.phase).toBe("host-starting");
    expect(initialRuntime?.startupDeadlineAt).toBe("2099-01-03T00:01:30.000Z");

    await instance.heartbeatHost(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {
        runtimeEpoch: entered.assignment!.runtimeEpoch,
        hostToken: entered.assignment!.hostToken,
        joinTarget: null
      },
      new Date("2099-01-03T00:01:00.000Z")
    );

    const refreshedRuntime = await repository.getRuntimeRecord(world.id, new Date("2099-01-03T00:01:00.000Z"));
    expect(refreshedRuntime?.phase).toBe("host-starting");
    expect(refreshedRuntime?.startupDeadlineAt).toBe("2099-01-03T00:02:30.000Z");
  });

  test("heartbeat reports host-finalizing for the same authorized runtime without refreshing it", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld({ playerUuid: "player-owner", playerName: "Owner" }, "Friends SMP", "friends-smp");

    const entered = await instance.enterSession(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {},
      new Date("2099-01-03T00:00:00.000Z")
    );

    await instance.heartbeatHost(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {
        runtimeEpoch: entered.assignment!.runtimeEpoch,
        hostToken: entered.assignment!.hostToken,
        joinTarget: "join.example"
      },
      new Date("2099-01-03T00:00:10.000Z")
    );
    await instance.beginFinalization(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      { runtimeEpoch: entered.assignment!.runtimeEpoch, hostToken: entered.assignment!.hostToken },
      new Date("2099-01-03T00:00:20.000Z")
    );

    const heartbeat = await instance.heartbeatHost(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {
        runtimeEpoch: entered.assignment!.runtimeEpoch,
        hostToken: entered.assignment!.hostToken,
        joinTarget: "stale.example"
      },
      new Date("2099-01-03T00:00:30.000Z")
    );

    expect(heartbeat.phase).toBe("host-finalizing");
    expect(heartbeat.runtimeEpoch).toBe(entered.assignment!.runtimeEpoch);
    expect(heartbeat.joinTarget).toBeNull();

    const runtime = await repository.getRuntimeRecord(world.id, new Date("2099-01-03T00:00:30.000Z"));
    expect(runtime?.phase).toBe("host-finalizing");
    expect(runtime?.joinTarget).toBeNull();
    expect(runtime?.lastProgressAt).toBe("2099-01-03T00:00:20.000Z");
    expect(runtime?.updatedAt).toBe("2099-01-03T00:00:20.000Z");
  });

  test("heartbeat still rejects mismatched authority after the runtime is finalizing", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld({ playerUuid: "player-owner", playerName: "Owner" }, "Friends SMP", "friends-smp");

    const entered = await instance.enterSession(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {},
      new Date("2099-01-03T00:00:00.000Z")
    );

    await instance.beginFinalization(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      { runtimeEpoch: entered.assignment!.runtimeEpoch, hostToken: entered.assignment!.hostToken },
      new Date("2099-01-03T00:00:10.000Z")
    );

    await expect(instance.heartbeatHost(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {
        runtimeEpoch: entered.assignment!.runtimeEpoch,
        hostToken: "wrong-token",
        joinTarget: "join.example"
      },
      new Date("2099-01-03T00:00:20.000Z")
    )).rejects.toMatchObject({ status: 409, code: "host_not_active" });
  });

  test("host-live timeout records warning, clears runtime, and clears waiters", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    await repository.upsertUser({ playerUuid: "player-guest", playerName: "Guest", createdAt: new Date().toISOString() });
    const world = await repository.createWorld({ playerUuid: "player-owner", playerName: "Owner" }, "Friends SMP", "friends-smp");
    await repository.addMembership({
      worldId: world.id,
      playerUuid: "player-guest",
      playerName: "Guest",
      role: "member",
      joinedAt: "2099-01-01T00:00:00.000Z",
      deletedAt: null
    });

    const entered = await instance.enterSession(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {},
      new Date("2099-01-03T00:00:00.000Z")
    );
    await instance.heartbeatHost(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      { runtimeEpoch: entered.assignment!.runtimeEpoch, hostToken: entered.assignment!.hostToken, joinTarget: "join.example" },
      new Date("2099-01-03T00:00:10.000Z")
    );
    await repository.upsertWaiterSession(world.id, { playerUuid: "player-guest", playerName: "Guest" }, "wait-guest", new Date("2099-01-03T00:00:20.000Z"));

    const runtime = await instance.runtimeStatus(
      { playerUuid: "player-guest", playerName: "Guest" },
      world.id,
      new Date("2099-01-03T00:01:41.000Z")
    );

    expect(runtime.phase).toBe("idle");
    expect(runtime.uncleanShutdownWarning).toEqual({
      hostUuid: "player-owner",
      hostPlayerName: "Owner",
      phase: "host-live",
      runtimeEpoch: entered.assignment!.runtimeEpoch,
      recordedAt: "2099-01-03T00:01:41.000Z"
    });
    expect(await repository.getRuntimeRecord(world.id, new Date("2099-01-03T00:01:41.000Z"))).toBeNull();
    expect(await repository.listActiveWaiters(world.id, new Date("2099-01-03T00:01:41.000Z"))).toEqual([]);
  });

  test("host-finalizing timeout uses finalization activity and records warning", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    await repository.upsertUser({ playerUuid: "player-guest", playerName: "Guest", createdAt: new Date().toISOString() });
    const world = await repository.createWorld({ playerUuid: "player-owner", playerName: "Owner" }, "Friends SMP", "friends-smp");
    await repository.addMembership({
      worldId: world.id,
      playerUuid: "player-guest",
      playerName: "Guest",
      role: "member",
      joinedAt: "2099-01-01T00:00:00.000Z",
      deletedAt: null
    });

    const entered = await instance.enterSession(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {},
      new Date("2099-01-03T00:00:00.000Z")
    );
    await instance.beginFinalization(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      { runtimeEpoch: entered.assignment!.runtimeEpoch, hostToken: entered.assignment!.hostToken },
      new Date("2099-01-03T00:00:10.000Z")
    );
    await instance.setHostStartupProgress(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {
        runtimeEpoch: entered.assignment!.runtimeEpoch,
        hostToken: entered.assignment!.hostToken,
        label: "Finalizing snapshot",
        mode: "indeterminate",
        fraction: null
      },
      new Date("2099-01-03T00:00:20.000Z")
    );

    const runtime = await instance.runtimeStatus(
      { playerUuid: "player-guest", playerName: "Guest" },
      world.id,
      new Date("2099-01-03T00:01:51.000Z")
    );

    expect(runtime.phase).toBe("idle");
    expect(runtime.uncleanShutdownWarning).toEqual({
      hostUuid: "player-owner",
      hostPlayerName: "Owner",
      phase: "host-finalizing",
      runtimeEpoch: entered.assignment!.runtimeEpoch,
      recordedAt: "2099-01-03T00:01:51.000Z"
    });
  });

  test("beginFinalization resets stale startup activity so finalization does not expire immediately", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    await repository.upsertUser({ playerUuid: "player-guest", playerName: "Guest", createdAt: new Date().toISOString() });
    const world = await repository.createWorld({ playerUuid: "player-owner", playerName: "Owner" }, "Friends SMP", "friends-smp");
    await repository.addMembership({
      worldId: world.id,
      playerUuid: "player-guest",
      playerName: "Guest",
      role: "member",
      joinedAt: "2099-01-01T00:00:00.000Z",
      deletedAt: null
    });

    const entered = await instance.enterSession(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {},
      new Date("2099-01-03T00:00:00.000Z")
    );
    await instance.setHostStartupProgress(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {
        runtimeEpoch: entered.assignment!.runtimeEpoch,
        hostToken: entered.assignment!.hostToken,
        label: "Syncing world",
        mode: "determinate",
        fraction: 0.42
      },
      new Date("2099-01-03T00:00:05.000Z")
    );
    await instance.heartbeatHost(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      { runtimeEpoch: entered.assignment!.runtimeEpoch, hostToken: entered.assignment!.hostToken, joinTarget: "join.example" },
      new Date("2099-01-03T00:00:10.000Z")
    );

    await instance.beginFinalization(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      { runtimeEpoch: entered.assignment!.runtimeEpoch, hostToken: entered.assignment!.hostToken },
      new Date("2099-01-03T00:01:39.000Z")
    );

    const runtime = await instance.runtimeStatus(
      { playerUuid: "player-guest", playerName: "Guest" },
      world.id,
      new Date("2099-01-03T00:01:40.000Z")
    );

    expect(runtime.phase).toBe("host-finalizing");
    expect(runtime.uncleanShutdownWarning).toBeNull();
    expect(runtime.lastProgressAt).toBe("2099-01-03T00:01:39.000Z");
    expect(runtime.startupProgress).toBeNull();
  });

  test("repeated finalization progress refresh keeps host-finalizing alive", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    await repository.upsertUser({ playerUuid: "player-guest", playerName: "Guest", createdAt: new Date().toISOString() });
    const world = await repository.createWorld({ playerUuid: "player-owner", playerName: "Owner" }, "Friends SMP", "friends-smp");
    await repository.addMembership({
      worldId: world.id,
      playerUuid: "player-guest",
      playerName: "Guest",
      role: "member",
      joinedAt: "2099-01-01T00:00:00.000Z",
      deletedAt: null
    });

    const entered = await instance.enterSession(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {},
      new Date("2099-01-03T00:00:00.000Z")
    );
    await instance.beginFinalization(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      { runtimeEpoch: entered.assignment!.runtimeEpoch, hostToken: entered.assignment!.hostToken },
      new Date("2099-01-03T00:00:10.000Z")
    );
    await instance.setHostStartupProgress(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {
        runtimeEpoch: entered.assignment!.runtimeEpoch,
        hostToken: entered.assignment!.hostToken,
        label: "Finalizing snapshot",
        mode: "indeterminate",
        fraction: null
      },
      new Date("2099-01-03T00:00:20.000Z")
    );
    await instance.setHostStartupProgress(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {
        runtimeEpoch: entered.assignment!.runtimeEpoch,
        hostToken: entered.assignment!.hostToken,
        label: "Finalizing snapshot",
        mode: "indeterminate",
        fraction: null
      },
      new Date("2099-01-03T00:01:35.000Z")
    );

    const runtime = await instance.runtimeStatus(
      { playerUuid: "player-guest", playerName: "Guest" },
      world.id,
      new Date("2099-01-03T00:02:50.000Z")
    );

    expect(runtime.phase).toBe("host-finalizing");
    expect(runtime.uncleanShutdownWarning).toBeNull();
    expect(runtime.lastProgressAt).toBe("2099-01-03T00:01:35.000Z");
  });

  test("world summaries stop showing finalizing after a host-finalizing timeout and preserve the launch warning", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld({ playerUuid: "player-owner", playerName: "Owner" }, "Friends SMP", "friends-smp");
    const startedAt = new Date(Date.now() - 10 * 60_000);
    const finalizingAt = new Date(startedAt.getTime() + 10_000);

    const entered = await instance.enterSession(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {},
      startedAt
    );
    await instance.beginFinalization(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      { runtimeEpoch: entered.assignment!.runtimeEpoch, hostToken: entered.assignment!.hostToken },
      finalizingAt
    );

    const [summary] = await instance.listWorlds({ playerUuid: "player-owner", playerName: "Owner" });
    const warned = await instance.enterSession(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {},
      new Date()
    );

    expect(summary?.status).toBe("idle");
    expect(summary?.activeHostUuid).toBeNull();
    expect(warned.action).toBe("warn-host");
    expect(warned.runtime.uncleanShutdownWarning).toMatchObject({
      hostUuid: "player-owner",
      hostPlayerName: "Owner",
      phase: "host-finalizing",
      runtimeEpoch: entered.assignment!.runtimeEpoch
    });
    expect(typeof warned.runtime.uncleanShutdownWarning?.recordedAt).toBe("string");
  });

  test("host-starting timeout does not record an unclean shutdown warning", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld({ playerUuid: "player-owner", playerName: "Owner" }, "Friends SMP", "friends-smp");

    await instance.enterSession(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {},
      new Date("2099-01-03T00:00:00.000Z")
    );

    const runtime = await instance.runtimeStatus(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      new Date("2099-01-03T00:01:31.000Z")
    );

    expect(runtime.phase).toBe("idle");
    expect(runtime.uncleanShutdownWarning).toBeNull();
  });

  test("host-starting timeout preserves the previous runtime epoch for the next host assignment", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld({ playerUuid: "player-owner", playerName: "Owner" }, "Friends SMP", "friends-smp");

    const firstLaunch = await instance.enterSession(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {},
      new Date("2099-01-03T00:00:00.000Z")
    );
    await instance.runtimeStatus(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      new Date("2099-01-03T00:01:31.000Z")
    );
    const secondLaunch = await instance.enterSession(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {},
      new Date("2099-01-03T00:01:32.000Z")
    );

    expect(firstLaunch.assignment?.runtimeEpoch).toBe(1);
    expect(secondLaunch.action).toBe("host");
    expect(secondLaunch.assignment?.runtimeEpoch).toBe(2);
  });

  test("idle worlds with warning require acknowledgement before hosting", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld({ playerUuid: "player-owner", playerName: "Owner" }, "Friends SMP", "friends-smp");
    await repository.setUncleanShutdownWarning(world.id, {
      hostUuid: "player-owner",
      hostPlayerName: "Owner",
      phase: "host-finalizing",
      runtimeEpoch: 1,
      recordedAt: "2099-01-03T00:00:00.000Z"
    });

    const warned = await instance.enterSession(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {},
      new Date("2099-01-03T00:00:10.000Z")
    );
    const acknowledged = await instance.enterSession(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      { acknowledgeUncleanShutdown: true },
      new Date("2099-01-03T00:00:11.000Z")
    );

    expect(warned.action).toBe("warn-host");
    expect(warned.assignment).toBeNull();
    expect(acknowledged.action).toBe("host");
    expect(acknowledged.assignment?.playerUuid).toBe("player-owner");
    expect(acknowledged.assignment?.runtimeEpoch).toBe(2);
  });

  test("warning survives forced relaunches and clears on a later graceful close", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld({ playerUuid: "player-owner", playerName: "Owner" }, "Friends SMP", "friends-smp");
    await repository.setUncleanShutdownWarning(world.id, {
      hostUuid: "player-owner",
      hostPlayerName: "Owner",
      phase: "host-live",
      runtimeEpoch: 1,
      recordedAt: "2099-01-03T00:00:00.000Z"
    });

    const firstLaunch = await instance.enterSession(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      { acknowledgeUncleanShutdown: true },
      new Date("2099-01-03T00:00:10.000Z")
    );
    await instance.releaseHost(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {
        graceful: false,
        runtimeEpoch: firstLaunch.assignment!.runtimeEpoch,
        hostToken: firstLaunch.assignment!.hostToken
      },
      new Date("2099-01-03T00:00:20.000Z")
    );

    const afterForced = await instance.runtimeStatus(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      new Date("2099-01-03T00:00:21.000Z")
    );
    expect(afterForced.uncleanShutdownWarning).not.toBeNull();

    const secondLaunch = await instance.enterSession(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      { acknowledgeUncleanShutdown: true },
      new Date("2099-01-03T00:00:22.000Z")
    );
    await instance.releaseHost(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {
        graceful: true,
        runtimeEpoch: secondLaunch.assignment!.runtimeEpoch,
        hostToken: secondLaunch.assignment!.hostToken
      },
      new Date("2099-01-03T00:00:30.000Z")
    );

    const afterGraceful = await instance.runtimeStatus(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      new Date("2099-01-03T00:00:31.000Z")
    );
    expect(afterGraceful.uncleanShutdownWarning).toBeNull();
  });

  test("owner can abandon stranded finalization but non-owner cannot", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld({ playerUuid: "player-owner", playerName: "Owner" }, "Friends SMP", "friends-smp");
    await repository.addMembership({
      worldId: world.id,
      playerUuid: "player-guest",
      playerName: "Guest",
      role: "member",
      joinedAt: "2026-01-02T00:00:00.000Z",
      deletedAt: null
    });

    await instance.claimHost({ playerUuid: "player-owner", playerName: "Owner" }, world.id, {}, new Date("2099-01-03T00:00:00.000Z"));
    await instance.handoffReady({ playerUuid: "player-guest", playerName: "Guest" }, world.id, { waiting: true }, new Date("2099-01-03T00:00:10.000Z"));
    await instance.beginFinalization({ playerUuid: "player-owner", playerName: "Owner" }, world.id, {}, new Date("2099-01-03T00:01:00.000Z"));

    await expect(instance.abandonFinalization(
      { playerUuid: "player-guest", playerName: "Guest" },
      world.id,
      {},
      new Date("2099-01-03T00:01:10.000Z")
    )).rejects.toMatchObject({ status: 403 });

    const abandoned = await instance.abandonFinalization(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {},
      new Date("2099-01-03T00:01:20.000Z")
    );
    const status = await instance.activeHost({ playerUuid: "player-guest", playerName: "Guest" }, world.id);

    expect(abandoned.status).toBe("handoff");
    expect(status.activeLease?.status).toBe("handoff");
    expect(status.nextHostUuid).toBe("player-guest");
  });

  test("discarded finalization blocks stale snapshot publication", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld({ playerUuid: "player-owner", playerName: "Owner" }, "Friends SMP", "friends-smp");
    await repository.addMembership({
      worldId: world.id,
      playerUuid: "player-guest",
      playerName: "Guest",
      role: "member",
      joinedAt: "2026-01-02T00:00:00.000Z",
      deletedAt: null
    });

    await instance.claimHost({ playerUuid: "player-owner", playerName: "Owner" }, world.id, {}, new Date("2099-01-03T00:00:00.000Z"));
    await instance.finalizeSnapshot(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {
        files: [{ path: "old.dat", hash: "old", size: 1, compressedSize: 1, storageKey: "old", contentType: "application/octet-stream", transferMode: "whole-gzip" }],
        packs: []
      },
      new Date("2099-01-03T00:00:01.000Z")
    );
    await instance.handoffReady({ playerUuid: "player-guest", playerName: "Guest" }, world.id, { waiting: true }, new Date("2099-01-03T00:00:10.000Z"));
    await instance.beginFinalization({ playerUuid: "player-owner", playerName: "Owner" }, world.id, {}, new Date("2099-01-03T00:01:00.000Z"));
    await instance.abandonFinalization({ playerUuid: "player-owner", playerName: "Owner" }, world.id, {}, new Date("2099-01-03T00:01:05.000Z"));

    await expect(instance.prepareUploads(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      { files: [], nonRegionPack: null, regionBundles: [] }
    )).rejects.toMatchObject({ status: 409, message: "SharedWorld host lease is no longer active for snapshot upload." });

    await expect(instance.finalizeSnapshot(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {
        files: [{ path: "new.dat", hash: "new", size: 2, compressedSize: 2, storageKey: "new", contentType: "application/octet-stream", transferMode: "whole-gzip" }],
        packs: []
      },
      new Date("2099-01-03T00:01:06.000Z")
    )).rejects.toMatchObject({ status: 409, message: "SharedWorld host lease is no longer active for snapshot upload." });

    await expect(instance.completeFinalization(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {},
      new Date("2099-01-03T00:01:07.000Z")
    )).rejects.toThrow("SharedWorld is not currently finalizing.");

    const latest = await instance.latestManifest({ playerUuid: "player-guest", playerName: "Guest" }, world.id);
    expect(latest?.files.map((file) => file.path)).toEqual(["old.dat"]);
  });
});
