import { describe, expect, test } from "bun:test";

import { MemorySharedWorldRepository } from "../../src/memory-repository.ts";
import { authVerifier, createBlobSigner, createTestService } from "../support/service-fixtures.ts";

describe("SharedWorldService progress and runtime", () => {
  test("explicit startup progress is rejected once host authority moved on", async () => {
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

    const entered = await instance.enterSession(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {},
      new Date("2099-01-03T00:00:00.000Z")
    );
    expect(entered.assignment).not.toBeNull();

    await instance.handoffReady(
      { playerUuid: "player-guest", playerName: "Guest" },
      world.id,
      { waiting: true },
      new Date("2099-01-03T00:00:05.000Z")
    );
    await instance.beginFinalization(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      { runtimeEpoch: entered.assignment!.runtimeEpoch, hostToken: entered.assignment!.hostToken },
      new Date("2099-01-03T00:00:10.000Z")
    );
    await instance.completeFinalization(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      { runtimeEpoch: entered.assignment!.runtimeEpoch, hostToken: entered.assignment!.hostToken },
      new Date("2099-01-03T00:00:20.000Z")
    );

    await expect(instance.setHostStartupProgress(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {
        runtimeEpoch: entered.assignment!.runtimeEpoch,
        hostToken: entered.assignment!.hostToken,
        label: "Too late",
        mode: "determinate",
        fraction: 1
      },
      new Date("2099-01-03T00:00:21.000Z")
    )).rejects.toMatchObject({ status: 409, code: "host_not_active" });
  });

  test("host startup progress is relayed in host status and cleared on release", async () => {
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
    await instance.setHostStartupProgress(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      { label: "Syncing world", mode: "determinate", fraction: 0.42 },
      new Date("2099-01-03T00:00:05.000Z")
    );

    const duringStartup = await instance.activeHost({ playerUuid: "player-guest", playerName: "Guest" }, world.id);
    expect(duringStartup.activeLease?.startupProgress).toEqual({
      label: "Syncing world",
      mode: "determinate",
      fraction: 0.42,
      updatedAt: "2099-01-03T00:00:05.000Z"
    });

    await instance.releaseHost(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      { graceful: false },
      new Date("2099-01-03T00:00:10.000Z")
    );

    const afterRelease = await instance.activeHost({ playerUuid: "player-guest", playerName: "Guest" }, world.id);
    expect(afterRelease.activeLease).toBeNull();
  });

  test("host startup progress is relayed while finalizing", async () => {
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
    await instance.beginFinalization(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {},
      new Date("2099-01-03T00:00:10.000Z")
    );
    await instance.setHostStartupProgress(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      { label: "Finalizing snapshot", mode: "indeterminate", fraction: null },
      new Date("2099-01-03T00:00:15.000Z")
    );

    const duringFinalization = await instance.activeHost({ playerUuid: "player-guest", playerName: "Guest" }, world.id);
    expect(duringFinalization.activeLease?.status).toBe("finalizing");
    expect(duringFinalization.activeLease?.startupProgress).toEqual({
      label: "Finalizing snapshot",
      mode: "indeterminate",
      fraction: null,
      updatedAt: "2099-01-03T00:00:15.000Z"
    });
  });

  test("late startup progress after finalization handoff is ignored", async () => {
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
    await instance.handoffReady({ playerUuid: "player-guest", playerName: "Guest" }, world.id, { waiting: true }, new Date("2099-01-03T00:00:05.000Z"));
    await instance.beginFinalization({ playerUuid: "player-owner", playerName: "Owner" }, world.id, {}, new Date("2099-01-03T00:00:10.000Z"));
    await instance.completeFinalization({ playerUuid: "player-owner", playerName: "Owner" }, world.id, {}, new Date("2099-01-03T00:00:20.000Z"));

    await expect(instance.setHostStartupProgress(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      { label: "Too late", mode: "determinate", fraction: 0.95 },
      new Date("2099-01-03T00:00:21.000Z")
    )).resolves.toBeNull();

    const afterFinalization = await instance.activeHost({ playerUuid: "player-guest", playerName: "Guest" }, world.id);
    expect(afterFinalization.activeLease?.status).toBe("handoff");
    expect(afterFinalization.activeLease?.startupProgress).toBeNull();
  });

  test("late startup progress after finalization to idle is ignored", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld({ playerUuid: "player-owner", playerName: "Owner" }, "Friends SMP", "friends-smp");

    await instance.claimHost({ playerUuid: "player-owner", playerName: "Owner" }, world.id, {}, new Date("2099-01-03T00:00:00.000Z"));
    await instance.beginFinalization({ playerUuid: "player-owner", playerName: "Owner" }, world.id, {}, new Date("2099-01-03T00:00:10.000Z"));
    await instance.completeFinalization({ playerUuid: "player-owner", playerName: "Owner" }, world.id, {}, new Date("2099-01-03T00:00:20.000Z"));

    await expect(instance.setHostStartupProgress(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      { label: "Too late", mode: "determinate", fraction: 1 },
      new Date("2099-01-03T00:00:21.000Z")
    )).resolves.toBeNull();

    const afterFinalization = await instance.activeHost({ playerUuid: "player-owner", playerName: "Owner" }, world.id);
    expect(afterFinalization.activeLease).toBeNull();
  });

  test("startup progress sent against a handoff lease is ignored", async () => {
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
    await instance.handoffReady({ playerUuid: "player-guest", playerName: "Guest" }, world.id, { waiting: true }, new Date("2099-01-03T00:00:05.000Z"));
    await instance.releaseHost(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      { graceful: true },
      new Date("2099-01-03T00:00:10.000Z")
    );

    await expect(instance.setHostStartupProgress(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      { label: "Should not stick", mode: "indeterminate", fraction: null },
      new Date("2099-01-03T00:00:11.000Z")
    )).resolves.toBeNull();

    const handoffStatus = await instance.activeHost({ playerUuid: "player-guest", playerName: "Guest" }, world.id);
    expect(handoffStatus.activeLease?.status).toBe("handoff");
    expect(handoffStatus.activeLease?.startupProgress).toBeNull();
  });
});
