import { describe, expect, test } from "bun:test";

import { NON_REGION_PACK_ID } from "../../../shared/src/index.ts";
import type { StorageProvider } from "../../src/storage.ts";
import { MemorySharedWorldRepository } from "../../src/memory-repository.ts";
import { authVerifier, createBlobSigner, createTestService } from "../support/service-fixtures.ts";

describe("SharedWorldService handoff", () => {
  function nonRegionPack(hash: string) {
    return {
      packId: NON_REGION_PACK_ID,
      hash,
      size: 12,
      fileCount: 1,
      files: [{ path: "level.dat", hash: `${hash}-file`, size: 12, contentType: "application/octet-stream" }]
    };
  }

  test("owner-first handoff beats older non-owner waiter", async () => {
    const repository = new MemorySharedWorldRepository();
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld({ playerUuid: "player-owner", playerName: "Owner" }, "Friends SMP", "friends-smp");
    await repository.addMembership({
      worldId: world.id,
      playerUuid: "player-b",
      playerName: "Bravo",
      role: "member",
      joinedAt: "2026-01-01T00:00:00.000Z",
      deletedAt: null
    });
    await repository.setHandoffWaiting(world.id, { playerUuid: "player-b", playerName: "Bravo" }, true, new Date());
    await repository.setHandoffWaiting(world.id, { playerUuid: "player-owner", playerName: "Owner" }, true, new Date());

    const next = await repository.chooseNextHost(world.id);
    expect(next?.playerUuid).toBe("player-owner");
  });

  test("stale waiters do not block a fresh host election", async () => {
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

    await repository.setHandoffWaiting(
      world.id,
      { playerUuid: "player-owner", playerName: "Owner" },
      true,
      new Date("2000-01-03T00:00:00.000Z")
    );

    const joinResolution = await instance.resolveJoin(
      { playerUuid: "player-guest", playerName: "Guest" },
      world.id
    );
    expect(joinResolution.action).toBe("host");
  });

  test("graceful release keeps handoff state and elects the next host", async () => {
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
    await instance.handoffReady({ playerUuid: "player-guest", playerName: "Guest" }, world.id, { waiting: true }, new Date("2099-01-03T00:01:00.000Z"));

    const released = await instance.releaseHost(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      { graceful: true },
      new Date("2099-01-03T00:02:00.000Z")
    );
    const status = await instance.activeHost({ playerUuid: "player-guest", playerName: "Guest" }, world.id);

    expect(released.nextHostUuid).toBe("player-guest");
    expect(status.activeLease?.status).toBe("handoff");
    expect(status.activeLease?.joinTarget).toBeNull();
    expect(status.nextHostUuid).toBe("player-guest");
  });

  test("graceful release with no waiter returns the world to idle", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld({ playerUuid: "player-owner", playerName: "Owner" }, "Friends SMP", "friends-smp");

    await instance.claimHost({ playerUuid: "player-owner", playerName: "Owner" }, world.id, {}, new Date("2099-01-03T00:00:00.000Z"));

    const released = await instance.releaseHost(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      { graceful: true },
      new Date("2099-01-03T00:02:00.000Z")
    );
    const status = await instance.activeHost({ playerUuid: "player-owner", playerName: "Owner" }, world.id);

    expect(released.nextHostUuid).toBeNull();
    expect(status.activeLease).toBeNull();
    expect(status.nextHostUuid).toBeNull();
  });

  test("forced release does not advertise a planned handoff even if another waiter remains", async () => {
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
    await instance.handoffReady({ playerUuid: "player-guest", playerName: "Guest" }, world.id, { waiting: true }, new Date("2099-01-03T00:01:00.000Z"));

    const released = await instance.releaseHost(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      { graceful: false },
      new Date("2099-01-03T00:02:00.000Z")
    );
    const status = await instance.activeHost({ playerUuid: "player-guest", playerName: "Guest" }, world.id);

    expect(released.nextHostUuid).toBeNull();
    expect(released.nextHostPlayerName).toBeNull();
    expect(status.nextHostUuid).toBe("player-guest");
  });

  test("claim host stays blocked until finalization completes", async () => {
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

    const duringFinalization = await instance.claimHost(
      { playerUuid: "player-guest", playerName: "Guest" },
      world.id,
      {},
      new Date("2099-01-03T00:01:10.000Z")
    );

    expect(duringFinalization.result).toBe("busy");

    const completed = await instance.completeFinalization(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {},
      new Date("2099-01-03T00:02:00.000Z")
    );
    const afterFinalization = await instance.claimHost(
      { playerUuid: "player-guest", playerName: "Guest" },
      world.id,
      {},
      new Date("2099-01-03T00:02:10.000Z")
    );

    expect(completed.status).toBe("handoff");
    expect(afterFinalization.result).toBe("claimed");
    expect(afterFinalization.lease?.hostUuid).toBe("player-guest");
  });

  test("old host cannot begin finalization after ownership moved to a newer epoch", async () => {
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
    const oldRuntime = await repository.getRuntimeRecord(world.id, new Date("2099-01-03T00:00:00.000Z"));
    expect(oldRuntime?.runtimeToken).toBeTruthy();

    await instance.handoffReady(
      { playerUuid: "player-guest", playerName: "Guest" },
      world.id,
      { waiting: true },
      new Date("2099-01-03T00:00:05.000Z")
    );
    await instance.beginFinalization(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      { runtimeEpoch: oldRuntime!.runtimeEpoch, hostToken: oldRuntime!.runtimeToken! },
      new Date("2099-01-03T00:00:10.000Z")
    );
    await instance.completeFinalization(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      { runtimeEpoch: oldRuntime!.runtimeEpoch, hostToken: oldRuntime!.runtimeToken! },
      new Date("2099-01-03T00:00:20.000Z")
    );
    await instance.claimHost(
      { playerUuid: "player-guest", playerName: "Guest" },
      world.id,
      { joinTarget: "example.next:25565" },
      new Date("2099-01-03T00:00:30.000Z")
    );

    await expect(instance.beginFinalization(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      { runtimeEpoch: oldRuntime!.runtimeEpoch, hostToken: oldRuntime!.runtimeToken! },
      new Date("2099-01-03T00:00:40.000Z")
    )).rejects.toMatchObject({ status: 409, code: "host_not_active" });
  });

  test("player who claims host is removed from waiter pool", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld({ playerUuid: "player-owner", playerName: "Owner" }, "Friends SMP", "friends-smp");

    await instance.handoffReady(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      { waiting: true },
      new Date("2099-01-03T00:00:00.000Z")
    );

    const claimed = await instance.claimHost(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {},
      new Date("2099-01-03T00:00:10.000Z")
    );
    expect(claimed.result).toBe("claimed");

    await instance.releaseHost(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      { graceful: true },
      new Date("2099-01-03T00:00:20.000Z")
    );

    const status = await instance.activeHost({ playerUuid: "player-owner", playerName: "Owner" }, world.id);
    expect(status.activeLease).toBeNull();
    expect(status.nextHostUuid).toBeNull();
  });

  test("only the elected handoff host can claim during graceful handoff", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld({ playerUuid: "player-owner", playerName: "Owner" }, "Friends SMP", "friends-smp");
    await repository.addMembership({
      worldId: world.id,
      playerUuid: "player-alpha",
      playerName: "Alpha",
      role: "member",
      joinedAt: "2026-01-01T00:00:00.000Z",
      deletedAt: null
    });
    await repository.addMembership({
      worldId: world.id,
      playerUuid: "player-bravo",
      playerName: "Bravo",
      role: "member",
      joinedAt: "2026-01-02T00:00:00.000Z",
      deletedAt: null
    });

    await instance.claimHost({ playerUuid: "player-owner", playerName: "Owner" }, world.id, {}, new Date("2099-01-03T00:00:00.000Z"));
    await instance.handoffReady({ playerUuid: "player-alpha", playerName: "Alpha" }, world.id, { waiting: true }, new Date("2099-01-03T00:01:00.000Z"));
    await instance.handoffReady({ playerUuid: "player-bravo", playerName: "Bravo" }, world.id, { waiting: true }, new Date("2099-01-03T00:01:30.000Z"));
    await instance.releaseHost(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      { graceful: true },
      new Date("2099-01-03T00:02:00.000Z")
    );

    const bravoClaim = await instance.claimHost(
      { playerUuid: "player-bravo", playerName: "Bravo" },
      world.id,
      {},
      new Date("2099-01-03T00:02:10.000Z")
    );
    const alphaClaim = await instance.claimHost(
      { playerUuid: "player-alpha", playerName: "Alpha" },
      world.id,
      {},
      new Date("2099-01-03T00:02:20.000Z")
    );

    expect(bravoClaim.result).toBe("busy");
    expect(alphaClaim.result).toBe("claimed");
    expect(alphaClaim.lease?.hostUuid).toBe("player-alpha");
  });

  test("handoff election moves to the next waiter when the chosen host cancels", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld({ playerUuid: "player-owner", playerName: "Owner" }, "Friends SMP", "friends-smp");
    await repository.addMembership({
      worldId: world.id,
      playerUuid: "player-alpha",
      playerName: "Alpha",
      role: "member",
      joinedAt: "2026-01-01T00:00:00.000Z",
      deletedAt: null
    });
    await repository.addMembership({
      worldId: world.id,
      playerUuid: "player-bravo",
      playerName: "Bravo",
      role: "member",
      joinedAt: "2026-01-02T00:00:00.000Z",
      deletedAt: null
    });

    await instance.claimHost({ playerUuid: "player-owner", playerName: "Owner" }, world.id, {}, new Date("2099-01-03T00:00:00.000Z"));
    await instance.handoffReady({ playerUuid: "player-alpha", playerName: "Alpha" }, world.id, { waiting: true }, new Date("2099-01-03T00:01:00.000Z"));
    await instance.handoffReady({ playerUuid: "player-bravo", playerName: "Bravo" }, world.id, { waiting: true }, new Date("2099-01-03T00:01:30.000Z"));
    await instance.releaseHost(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      { graceful: true },
      new Date("2099-01-03T00:02:00.000Z")
    );

    const initialStatus = await instance.activeHost({ playerUuid: "player-bravo", playerName: "Bravo" }, world.id);
    expect(initialStatus.nextHostUuid).toBe("player-alpha");

    await instance.handoffReady({ playerUuid: "player-alpha", playerName: "Alpha" }, world.id, { waiting: false }, new Date("2099-01-03T00:02:10.000Z"));

    const updatedStatus = await instance.activeHost({ playerUuid: "player-bravo", playerName: "Bravo" }, world.id);
    expect(updatedStatus.nextHostUuid).toBe("player-bravo");

    const bravoClaim = await instance.claimHost(
      { playerUuid: "player-bravo", playerName: "Bravo" },
      world.id,
      {},
      new Date("2099-01-03T00:02:20.000Z")
    );
    expect(bravoClaim.result).toBe("claimed");
    expect(bravoClaim.lease?.hostUuid).toBe("player-bravo");
  });

  test("kicked hosts can still finalize gracefully and then hand off", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld({ playerUuid: "player-owner", playerName: "Owner" }, "Friends SMP", "friends-smp");
    await repository.addMembership({
      worldId: world.id,
      playerUuid: "player-host",
      playerName: "Host",
      role: "member",
      joinedAt: "2026-01-02T00:00:00.000Z",
      deletedAt: null
    });

    await instance.claimHost(
      { playerUuid: "player-host", playerName: "Host" },
      world.id,
      { joinTarget: "example.test:25565" },
      new Date("2099-01-03T00:00:00.000Z")
    );
    await instance.handoffReady(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      { waiting: true },
      new Date("2099-01-03T00:00:30.000Z")
    );

    await instance.kickMember(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      "player-host",
      new Date("2099-01-03T00:01:00.000Z")
    );

    const ownerStatusBeforeFinalize = await instance.activeHost({ playerUuid: "player-owner", playerName: "Owner" }, world.id);
    expect(ownerStatusBeforeFinalize.activeLease?.hostUuid).toBe("player-host");
    expect(ownerStatusBeforeFinalize.activeLease?.revokedAt).toBe("2099-01-03T00:01:00.000Z");

    await expect(
      instance.heartbeatHost(
        { playerUuid: "player-host", playerName: "Host" },
        world.id,
        { joinTarget: "example.test:25565" },
        new Date("2099-01-03T00:01:10.000Z")
      )
    ).rejects.toMatchObject({ code: "membership_revoked", status: 403 });

    await instance.beginFinalization(
      { playerUuid: "player-host", playerName: "Host" },
      world.id,
      {},
      new Date("2099-01-03T00:01:20.000Z")
    );
    await instance.prepareUploads(
      { playerUuid: "player-host", playerName: "Host" },
      world.id,
      { files: [], nonRegionPack: null, regionBundles: [] }
    );
    await instance.finalizeSnapshot(
      { playerUuid: "player-host", playerName: "Host" },
      world.id,
      { files: [], packs: [] },
      new Date("2099-01-03T00:01:30.000Z")
    );
    const finalization = await instance.completeFinalization(
      { playerUuid: "player-host", playerName: "Host" },
      world.id,
      {},
      new Date("2099-01-03T00:01:40.000Z")
    );

    expect(finalization.status).toBe("handoff");
    expect(finalization.nextHostUuid).toBe("player-owner");
  });

  test("kicked hosts can still upload finalization blobs with current runtime headers", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const uploaded: Array<{ storageKey: string; contentType: string }> = [];
    const storageProvider: StorageProvider = {
      provider: "google-drive",
      async exists() {
        return false;
      },
      async put(_binding, storageKey, _body, contentType) {
        uploaded.push({ storageKey, contentType });
      },
      async get() {
        return null;
      },
      async delete() {
      },
      async quota() {
        return { usedBytes: null, totalBytes: null };
      }
    };
    const instance = createTestService(repository, authVerifier, signer, storageProvider, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld(
      { playerUuid: "player-owner", playerName: "Owner" },
      "Friends SMP",
      "friends-smp",
      { provider: "google-drive", storageAccountId: "storage-account-1" }
    );
    await repository.addMembership({
      worldId: world.id,
      playerUuid: "player-host",
      playerName: "Host",
      role: "member",
      joinedAt: "2099-01-02T00:00:00.000Z",
      deletedAt: null
    });

    await instance.claimHost(
      { playerUuid: "player-host", playerName: "Host" },
      world.id,
      { joinTarget: "example.test:25565" },
      new Date("2099-01-03T00:00:00.000Z")
    );
    await instance.kickMember(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      "player-host",
      new Date("2099-01-03T00:01:00.000Z")
    );
    const plan = await instance.prepareUploads(
      { playerUuid: "player-host", playerName: "Host" },
      world.id,
      { files: [], nonRegionPack: nonRegionPack("final-pack"), regionBundles: [] }
    );
    const runtimeEpochHeader = plan.nonRegionPackUpload?.fullUpload?.headers["x-sharedworld-runtime-epoch"] ?? "";
    const hostToken = plan.nonRegionPackUpload?.fullUpload?.headers["x-sharedworld-host-token"] ?? "";
    const uploadHeaders = plan.nonRegionPackUpload?.fullUpload?.headers ?? {};
    expect(runtimeEpochHeader).toBe("1");
    expect(typeof hostToken).toBe("string");
    expect(hostToken.length).toBeGreaterThan(0);

    await instance.uploadStorageBlob(
      { playerUuid: "player-host", playerName: "Host" },
      world.id,
      plan.nonRegionPackUpload?.fullStorageKey ?? "packs/final.pack",
      new Request("https://example.invalid/upload", {
        method: "PUT",
        headers: {
          "content-type": "application/octet-stream",
          ...uploadHeaders
        },
        body: "payload"
      })
    );

    expect(uploaded).toEqual([{
      storageKey: plan.nonRegionPackUpload?.fullStorageKey ?? "packs/final.pack",
      contentType: "application/octet-stream"
    }]);
  });

  test("blob uploads require runtime auth headers", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const uploaded: Array<{ storageKey: string; contentType: string }> = [];
    const storageProvider: StorageProvider = {
      provider: "google-drive",
      async exists() {
        return false;
      },
      async put(_binding, storageKey, _body, contentType) {
        uploaded.push({ storageKey, contentType });
      },
      async get() {
        return null;
      },
      async delete() {
      },
      async quota() {
        return { usedBytes: null, totalBytes: null };
      }
    };
    const instance = createTestService(repository, authVerifier, signer, storageProvider, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld(
      { playerUuid: "player-owner", playerName: "Owner" },
      "Friends SMP",
      "friends-smp",
      { provider: "google-drive", storageAccountId: "storage-account-1" }
    );

    await instance.claimHost(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      { joinTarget: "example.test:25565" },
      new Date("2099-01-03T00:00:00.000Z")
    );

    await expect(Promise.resolve().then(() => instance.uploadStorageBlob(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      "packs/full/mi/missing-runtime.pack",
      new Request("https://example.invalid/upload", {
        method: "PUT",
        headers: {
          "content-type": "application/octet-stream"
        },
        body: "payload"
      })
    ))).rejects.toMatchObject({
      status: 409,
      code: "host_not_active"
    });

    expect(uploaded).toEqual([]);
  });

  test("stale blob upload headers are rejected after same-player host authority rotates", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const uploaded: Array<{ storageKey: string; contentType: string }> = [];
    const storageProvider: StorageProvider = {
      provider: "google-drive",
      async exists() {
        return false;
      },
      async put(_binding, storageKey, _body, contentType) {
        uploaded.push({ storageKey, contentType });
      },
      async get() {
        return null;
      },
      async delete() {
      },
      async quota() {
        return { usedBytes: null, totalBytes: null };
      }
    };
    const instance = createTestService(repository, authVerifier, signer, storageProvider, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });

    const initialLeaseAt = new Date("2099-01-03T00:00:00.000Z");
    const created = await instance.createWorld(
      { playerUuid: "player-owner", playerName: "Owner" },
      { name: "Friends SMP" },
      initialLeaseAt
    );
    const worldId = created.world.id;
    const firstPlan = await instance.prepareUploads(
      { playerUuid: "player-owner", playerName: "Owner" },
      worldId,
      {
        runtimeEpoch: created.initialUploadAssignment.runtimeEpoch,
        hostToken: created.initialUploadAssignment.hostToken,
        files: [],
        nonRegionPack: nonRegionPack("epoch-one"),
        regionBundles: []
      }
    );
    const staleStorageKey = firstPlan.nonRegionPackUpload?.fullStorageKey;
    const staleHeaders = firstPlan.nonRegionPackUpload?.fullUpload?.headers;
    const staleRuntimeEpochHeader = staleHeaders?.["x-sharedworld-runtime-epoch"] ?? "";

    await instance.releaseHost(
      { playerUuid: "player-owner", playerName: "Owner" },
      worldId,
      {
        graceful: false,
        runtimeEpoch: created.initialUploadAssignment.runtimeEpoch,
        hostToken: created.initialUploadAssignment.hostToken
      },
      new Date("2099-01-03T00:00:05.000Z")
    );

    const reacquired = await instance.enterSession(
      { playerUuid: "player-owner", playerName: "Owner" },
      worldId,
      {},
      new Date("2099-01-03T00:00:10.000Z")
    );

    expect(reacquired.assignment?.playerUuid).toBe("player-owner");
    expect(reacquired.assignment?.runtimeEpoch).toBe(2);
    expect(Number(staleRuntimeEpochHeader)).toBe(1);

    await expect(Promise.resolve().then(() => instance.uploadStorageBlob(
      { playerUuid: "player-owner", playerName: "Owner" },
      worldId,
      staleStorageKey ?? "packs/full/ep/epoch-one.pack",
      new Request("https://example.invalid/upload", {
        method: "PUT",
        headers: {
          "content-type": "application/octet-stream",
          ...(staleHeaders ?? {})
        },
        body: "payload"
      })
    ))).rejects.toMatchObject({
      status: 409,
      message: "SharedWorld host lease is no longer active for snapshot upload."
    });

    expect(uploaded).toEqual([]);
  });

  test("kicking the next waiting host candidate reselects handoff", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld({ playerUuid: "player-owner", playerName: "Owner" }, "Friends SMP", "friends-smp");
    await repository.addMembership({
      worldId: world.id,
      playerUuid: "player-alpha",
      playerName: "Alpha",
      role: "member",
      joinedAt: "2026-01-02T00:00:00.000Z",
      deletedAt: null
    });
    await repository.addMembership({
      worldId: world.id,
      playerUuid: "player-bravo",
      playerName: "Bravo",
      role: "member",
      joinedAt: "2026-01-02T00:01:00.000Z",
      deletedAt: null
    });

    await instance.claimHost(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      { joinTarget: "example.test:25565" },
      new Date("2099-01-03T00:00:00.000Z")
    );
    await instance.handoffReady(
      { playerUuid: "player-alpha", playerName: "Alpha" },
      world.id,
      { waiting: true },
      new Date("2099-01-03T00:00:05.000Z")
    );
    await instance.handoffReady(
      { playerUuid: "player-bravo", playerName: "Bravo" },
      world.id,
      { waiting: true },
      new Date("2099-01-03T00:00:10.000Z")
    );
    await instance.beginFinalization(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {},
      new Date("2099-01-03T00:00:20.000Z")
    );

    await instance.kickMember(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      "player-alpha",
      new Date("2099-01-03T00:00:25.000Z")
    );

    const finalization = await instance.completeFinalization(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {},
      new Date("2099-01-03T00:00:30.000Z")
    );

    expect(finalization.status).toBe("handoff");
    expect(finalization.nextHostUuid).toBe("player-bravo");
  });

  test("guest presence entries are cleared after completeFinalization handoff", async () => {
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
    await instance.setPlayerPresence(
      { playerUuid: "player-guest", playerName: "Guest" },
      world.id,
      { present: true, guestSessionEpoch: 1, presenceSequence: 1 },
      new Date("2099-01-03T00:00:10.000Z")
    );
    await instance.handoffReady(
      { playerUuid: "player-guest", playerName: "Guest" },
      world.id,
      { waiting: true },
      new Date("2099-01-03T00:00:15.000Z")
    );

    const beforeWorlds = await instance.listWorlds({ playerUuid: "player-owner", playerName: "Owner" });
    expect(beforeWorlds[0].onlinePlayerCount).toBe(2);

    await instance.beginFinalization(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {},
      new Date("2099-01-03T00:01:00.000Z")
    );
    await instance.completeFinalization(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {},
      new Date("2099-01-03T00:01:10.000Z")
    );

    const afterWorlds = await instance.listWorlds({ playerUuid: "player-owner", playerName: "Owner" });
    expect(afterWorlds[0].onlinePlayerCount).toBe(0);
    expect(afterWorlds[0].onlinePlayerNames).toEqual([]);
  });

  test("guest presence entries are cleared after releaseHost", async () => {
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
    await instance.setPlayerPresence(
      { playerUuid: "player-guest", playerName: "Guest" },
      world.id,
      { present: true, guestSessionEpoch: 1, presenceSequence: 1 },
      new Date("2099-01-03T00:00:10.000Z")
    );

    const beforeWorlds = await instance.listWorlds({ playerUuid: "player-owner", playerName: "Owner" });
    expect(beforeWorlds[0].onlinePlayerCount).toBe(2);

    await instance.releaseHost(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      { graceful: true },
      new Date("2099-01-03T00:01:00.000Z")
    );

    const afterWorlds = await instance.listWorlds({ playerUuid: "player-owner", playerName: "Owner" });
    expect(afterWorlds[0].onlinePlayerCount).toBe(0);
    expect(afterWorlds[0].onlinePlayerNames).toEqual([]);
  });
});
