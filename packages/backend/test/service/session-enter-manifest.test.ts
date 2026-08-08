import { describe, expect, test } from "bun:test";

import type { RequestContext } from "../../src/repository.ts";
import { clientVersionAtLeast } from "../../src/http.ts";
import { createSqliteRepository } from "../support/sqlite-d1.ts";
import { authVerifier, createBlobSigner, createTestService } from "../support/service-fixtures.ts";

/**
 * 0.3.2+ clients decide "does this world have a snapshot" from
 * world.lastSnapshotId and never read the enter response's manifest body, so
 * the backend omits it for them (large worlds carry thousands of manifest
 * entries — loading and serializing them blew the Worker CPU budget). Older
 * clients null-check the field and must keep receiving the full manifest.
 */
describe("session enter manifest version gate", () => {
  const OWNER: RequestContext = { playerUuid: "player-owner", playerName: "Owner" };
  const NOW = new Date("2099-01-01T12:00:00.000Z");

  async function setup() {
    const repository = createSqliteRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    await repository.upsertUser({ playerUuid: OWNER.playerUuid, playerName: OWNER.playerName, createdAt: NOW.toISOString() });
    const world = await repository.createWorld(OWNER, "Gate World", "gate-world");
    const snapshot = await repository.finalizeSnapshot(world.id, OWNER, {
      files: [],
      packs: [
        {
          packId: "non-region",
          hash: "pack-hash-1",
          size: 40,
          storageKey: "packs/full/one.pack",
          transferMode: "pack-full",
          files: [{ path: "level.dat", hash: "hash-level", size: 40, contentType: "application/octet-stream" }]
        }
      ],
      baseSnapshotId: null
    }, NOW);
    return { instance, worldId: world.id, snapshotId: snapshot.snapshotId };
  }

  test("a 0.3.1 client keeps receiving the full latest manifest", async () => {
    const { instance, worldId, snapshotId } = await setup();
    const entered = await instance.enterSession({ ...OWNER, clientVersion: "0.3.1" }, worldId, {}, NOW);
    expect(entered.latestManifest?.snapshotId).toBe(snapshotId);
    expect(entered.latestManifest?.packs[0]?.files.map((file) => file.path)).toEqual(["level.dat"]);
    expect(entered.world.lastSnapshotId).toBe(snapshotId);
  });

  test("a client without a version header keeps receiving the full latest manifest", async () => {
    const { instance, worldId, snapshotId } = await setup();
    const entered = await instance.enterSession(OWNER, worldId, {}, NOW);
    expect(entered.latestManifest?.snapshotId).toBe(snapshotId);
  });

  test("a 0.3.2 client gets no manifest body but still sees lastSnapshotId", async () => {
    const { instance, worldId, snapshotId } = await setup();
    const entered = await instance.enterSession({ ...OWNER, clientVersion: "0.3.2" }, worldId, {}, NOW);
    expect(entered.latestManifest).toBeNull();
    expect(entered.world.lastSnapshotId).toBe(snapshotId);
    expect(entered.action).toBe("host");
  });

  test("clientVersionAtLeast parses defensively toward the legacy shape", () => {
    expect(clientVersionAtLeast("0.3.2", 0, 3, 2)).toBe(true);
    expect(clientVersionAtLeast("0.3.2+mc1.21.11", 0, 3, 2)).toBe(true);
    expect(clientVersionAtLeast("0.4.0", 0, 3, 2)).toBe(true);
    expect(clientVersionAtLeast("1.0.0", 0, 3, 2)).toBe(true);
    expect(clientVersionAtLeast("0.3.1", 0, 3, 2)).toBe(false);
    expect(clientVersionAtLeast("0.3.10", 0, 3, 2)).toBe(true);
    expect(clientVersionAtLeast(null, 0, 3, 2)).toBe(false);
    expect(clientVersionAtLeast("", 0, 3, 2)).toBe(false);
    expect(clientVersionAtLeast("dev", 0, 3, 2)).toBe(false);
  });
});
