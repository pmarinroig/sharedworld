import { describe, expect, test } from "bun:test";

import type { RequestContext } from "../../src/repository.ts";
import { createSqliteRepository } from "../support/sqlite-d1.ts";

/**
 * [S11 fixed] finalizeSnapshot writes through one transactional batch, so a
 * failure mid-write can never leave a partial snapshot behind (a partial row
 * would otherwise become the world's "latest" manifest).
 */
describe("finalizeSnapshot atomicity", () => {
  const OWNER: RequestContext = { playerUuid: "player-owner", playerName: "Owner" };
  const NOW = new Date("2099-01-01T12:00:00.000Z");

  test("a failing file row rolls back the whole snapshot", async () => {
    const repository = createSqliteRepository();
    const world = await repository.createWorld(OWNER, "Batch World", "batch-world");

    const request = {
      files: [
        { path: "level.dat", hash: "h1", size: 10, compressedSize: 5, storageKey: "blobs/h1", contentType: "application/octet-stream" },
        // NOT NULL violation on path: the second insert fails after the
        // snapshot row and the first file row were already queued.
        { path: null as unknown as string, hash: "h2", size: 10, compressedSize: 5, storageKey: "blobs/h2", contentType: "application/octet-stream" }
      ]
    };

    expect(repository.finalizeSnapshot(world.id, OWNER, request, NOW)).rejects.toThrow();
    expect(await repository.listSnapshotsForWorld(world.id)).toHaveLength(0);
    expect(await repository.getLatestSnapshot(world.id)).toBeNull();
  });

  test("a valid snapshot still lands intact", async () => {
    const repository = createSqliteRepository();
    const world = await repository.createWorld(OWNER, "Batch World", "batch-world");
    const manifest = await repository.finalizeSnapshot(world.id, OWNER, {
      files: [{ path: "level.dat", hash: "h1", size: 10, compressedSize: 5, storageKey: "blobs/h1", contentType: "application/octet-stream" }]
    }, NOW);
    expect(manifest.files).toHaveLength(1);
    expect(await repository.listSnapshotsForWorld(world.id)).toHaveLength(1);
  });
});
