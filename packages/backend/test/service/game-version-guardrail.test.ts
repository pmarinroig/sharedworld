import { describe, expect, test } from "bun:test";

import { createSqliteRepository } from "../support/sqlite-d1.ts";
import { authVerifier, createBlobSigner, createTestService } from "../support/service-fixtures.ts";

describe("cross-version guardrail fields", () => {
  test("finalizeSnapshot records the world's data/minecraft version and surfaces it on summaries", async () => {
    const repository = createSqliteRepository();
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

    await instance.finalizeSnapshot(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {
        runtimeEpoch: entered.assignment!.runtimeEpoch,
        hostToken: entered.assignment!.hostToken,
        dataVersion: 3465,
        minecraftVersion: "1.20.1",
        files: [{ path: "level.dat", hash: "h1", size: 1, compressedSize: 1, storageKey: "k1", contentType: "application/octet-stream", transferMode: "whole-gzip" }],
        packs: []
      },
      new Date("2099-01-03T00:00:05.000Z")
    );

    const summaries = await repository.listSnapshotSummaries(world.id);
    expect(summaries[0]?.dataVersion).toBe(3465);
    expect(summaries[0]?.minecraftVersion).toBe("1.20.1");

    const worlds = await instance.listWorlds({ playerUuid: "player-owner", playerName: "Owner" });
    const summary = worlds.find((entry) => entry.id === world.id);
    expect(summary?.lastSnapshotDataVersion).toBe(3465);
    expect(summary?.lastSnapshotMinecraftVersion).toBe("1.20.1");
  });

  test("legacy finalize without versions stays null end to end", async () => {
    const repository = createSqliteRepository();
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

    await instance.finalizeSnapshot(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {
        runtimeEpoch: entered.assignment!.runtimeEpoch,
        hostToken: entered.assignment!.hostToken,
        files: [{ path: "level.dat", hash: "h1", size: 1, compressedSize: 1, storageKey: "k1", contentType: "application/octet-stream", transferMode: "whole-gzip" }],
        packs: []
      },
      new Date("2099-01-03T00:00:05.000Z")
    );

    const worlds = await instance.listWorlds({ playerUuid: "player-owner", playerName: "Owner" });
    const summary = worlds.find((entry) => entry.id === world.id);
    expect(summary?.lastSnapshotDataVersion).toBeNull();
    expect(summary?.lastSnapshotMinecraftVersion).toBeNull();
  });

  test("host heartbeat stamps and preserves the host's Minecraft version", async () => {
    const repository = createSqliteRepository();
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

    const afterFirstBeat = await instance.heartbeatHost(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {
        runtimeEpoch: entered.assignment!.runtimeEpoch,
        hostToken: entered.assignment!.hostToken,
        joinTarget: "join.example",
        minecraftVersion: "1.21.1"
      },
      new Date("2099-01-03T00:00:10.000Z")
    );
    expect(afterFirstBeat.hostMinecraftVersion).toBe("1.21.1");

    const afterSecondBeat = await instance.heartbeatHost(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      {
        runtimeEpoch: entered.assignment!.runtimeEpoch,
        hostToken: entered.assignment!.hostToken,
        joinTarget: "join.example"
      },
      new Date("2099-01-03T00:00:20.000Z")
    );
    expect(afterSecondBeat.hostMinecraftVersion).toBe("1.21.1");

    const status = await instance.runtimeStatus(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      new Date("2099-01-03T00:00:30.000Z")
    );
    expect(status.hostMinecraftVersion).toBe("1.21.1");
  });

  test("restoring a backup keeps its game-version stamps so the guardrail stays armed", async () => {
    const repository = createSqliteRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld({ playerUuid: "player-owner", playerName: "Owner" }, "Friends SMP", "friends-smp");
    const owner = { playerUuid: "player-owner", playerName: "Owner" };

    const entered = await instance.enterSession(owner, world.id, {}, new Date("2099-01-03T00:00:00.000Z"));
    const auth = { runtimeEpoch: entered.assignment!.runtimeEpoch, hostToken: entered.assignment!.hostToken };
    const older = await instance.finalizeSnapshot(owner, world.id, {
      ...auth,
      dataVersion: 3465,
      minecraftVersion: "1.20.1",
      files: [{ path: "level.dat", hash: "h1", size: 1, compressedSize: 1, storageKey: "k1", contentType: "application/octet-stream", transferMode: "whole-gzip" }],
      packs: []
    }, new Date("2099-01-03T00:00:05.000Z"));
    await instance.finalizeSnapshot(owner, world.id, {
      ...auth,
      dataVersion: 4189,
      minecraftVersion: "1.21.11",
      files: [{ path: "level.dat", hash: "h2", size: 1, compressedSize: 1, storageKey: "k2", contentType: "application/octet-stream", transferMode: "whole-gzip" }],
      packs: []
    }, new Date("2099-01-03T00:00:10.000Z"));
    await instance.releaseHost(owner, world.id, { ...auth, graceful: true }, new Date("2099-01-03T00:00:15.000Z"));

    await instance.restoreSnapshot(owner, world.id, older.snapshotId, new Date("2099-01-03T00:00:20.000Z"));

    const worlds = await instance.listWorlds(owner);
    const summary = worlds.find((entry) => entry.id === world.id);
    expect(summary?.lastSnapshotDataVersion).toBe(3465);
    expect(summary?.lastSnapshotMinecraftVersion).toBe("1.20.1");
    const summaries = await repository.listSnapshotSummaries(world.id);
    expect(summaries[0]?.isLatest).toBe(true);
    expect(summaries[0]?.dataVersion).toBe(3465);
  });

  test("backups cannot be restored while the world is being hosted", async () => {
    const repository = createSqliteRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld({ playerUuid: "player-owner", playerName: "Owner" }, "Friends SMP", "friends-smp");
    const owner = { playerUuid: "player-owner", playerName: "Owner" };

    const entered = await instance.enterSession(owner, world.id, {}, new Date("2099-01-03T00:00:00.000Z"));
    const auth = { runtimeEpoch: entered.assignment!.runtimeEpoch, hostToken: entered.assignment!.hostToken };
    const snapshot = await instance.finalizeSnapshot(owner, world.id, {
      ...auth,
      files: [{ path: "level.dat", hash: "h1", size: 1, compressedSize: 1, storageKey: "k1", contentType: "application/octet-stream", transferMode: "whole-gzip" }],
      packs: []
    }, new Date("2099-01-03T00:00:05.000Z"));

    // Restoring would swap the latest snapshot out from under the live host's
    // in-flight delta bases.
    await expect(instance.restoreSnapshot(owner, world.id, snapshot.snapshotId, new Date("2099-01-03T00:00:10.000Z")))
      .rejects.toThrow("cannot be restored while the world is being hosted");

    await instance.releaseHost(owner, world.id, { ...auth, graceful: true }, new Date("2099-01-03T00:00:15.000Z"));
    await instance.restoreSnapshot(owner, world.id, snapshot.snapshotId, new Date("2099-01-03T00:00:20.000Z"));
  });
});
