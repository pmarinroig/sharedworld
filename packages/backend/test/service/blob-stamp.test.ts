import { describe, expect, test } from "bun:test";

import type { StorageProvider } from "../../src/storage.ts";

import { BLOB_STAMP_TTL_MS, DOWNLOAD_STAMP_TTL_MS, mintBlobStamp, mintDownloadStamp, verifyBlobStamp, verifyDownloadStamp } from "../../src/service/blob-stamp.ts";
import { createBlobBucket, createBlobSigner, createTestService } from "../support/service-fixtures.ts";
import { createSqliteRepository } from "../support/sqlite-d1.ts";

const NOW = new Date("2026-01-01T10:00:00.000Z");
const SCOPE = { worldId: "world-1", storageKey: "packs/full/ab/abc.pack" };
const CLAIMS = { worldId: SCOPE.worldId, runtimeEpoch: 3, storageKey: SCOPE.storageKey };

describe("blob stamp mint/verify", () => {
  const env = { SIGNING_SECRET: "current-secret" };

  test("round-trips authentic claims", async () => {
    const stamp = await mintBlobStamp(env, CLAIMS, NOW);
    expect(stamp).toMatch(/^v1\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/);
    expect(await verifyBlobStamp(env, stamp!, SCOPE, new Date(NOW.getTime() + 1000))).toEqual({ runtimeEpoch: 3 });
  });

  test("expires after its TTL", async () => {
    const stamp = await mintBlobStamp(env, CLAIMS, NOW);
    const justBefore = new Date(NOW.getTime() + BLOB_STAMP_TTL_MS - 1000);
    const justAfter = new Date(NOW.getTime() + BLOB_STAMP_TTL_MS + 1000);
    expect(await verifyBlobStamp(env, stamp!, SCOPE, justBefore)).not.toBeNull();
    expect(await verifyBlobStamp(env, stamp!, SCOPE, justAfter)).toBeNull();
  });

  test("rejects a foreign key but accepts the previous secret", async () => {
    const stamp = await mintBlobStamp(env, CLAIMS, NOW);
    expect(await verifyBlobStamp({ SIGNING_SECRET: "other-secret" }, stamp!, SCOPE, NOW)).toBeNull();
    expect(await verifyBlobStamp(
      { SIGNING_SECRET: "rotated-new", SIGNING_SECRET_PREVIOUS: "current-secret" },
      stamp!,
      SCOPE,
      NOW
    )).toEqual({ runtimeEpoch: 3 });
  });

  test("rejects scope mismatches and malformed stamps", async () => {
    const stamp = await mintBlobStamp(env, CLAIMS, NOW);
    expect(await verifyBlobStamp(env, stamp!, { ...SCOPE, worldId: "world-2" }, NOW)).toBeNull();
    expect(await verifyBlobStamp(env, stamp!, { ...SCOPE, storageKey: "packs/other.pack" }, NOW)).toBeNull();
    expect(await verifyBlobStamp(env, "v1.garbage", SCOPE, NOW)).toBeNull();
    expect(await verifyBlobStamp(env, "v2.a.b", SCOPE, NOW)).toBeNull();
    expect(await verifyBlobStamp(env, "not-a-stamp", SCOPE, NOW)).toBeNull();
  });

  test("no secret: minting is skipped and verification always fails", async () => {
    expect(await mintBlobStamp({}, CLAIMS, NOW)).toBeNull();
    const stamp = await mintBlobStamp(env, CLAIMS, NOW);
    expect(await verifyBlobStamp({}, stamp!, SCOPE, NOW)).toBeNull();
  });
});

describe("stamped relay uploads", () => {
  const host = { playerUuid: "player-host", playerName: "Host" };

  async function fixture(env: Record<string, string>) {
    const repository = createSqliteRepository();
    const { signer } = createBlobSigner();
    const uploaded: string[] = [];
    const storageProvider: StorageProvider = {
      provider: "google-drive",
      async exists() { return false; },
      async put(_binding, storageKey) { uploaded.push(storageKey); },
      async get() { return null; },
      async delete() {},
      async quota() { return { usedBytes: null, totalBytes: null }; }
    };
    const instance = createTestService(repository, signer, storageProvider, env);
    await repository.upsertUser({ ...host, createdAt: new Date().toISOString() });
    const world = await repository.createWorld(host, "Friends SMP", "friends-smp", {
      provider: "google-drive",
      storageAccountId: "storage-account-1"
    });
    await instance.claimHost(host, world.id, { joinTarget: "example.test:25565" }, new Date());
    const plan = await instance.prepareUploads(host, world.id, {
      files: [],
      nonRegionPack: {
        packId: "non-region",
        hash: "hash-1",
        size: 7,
        fileCount: 1,
        files: [{ path: "level.dat", hash: "hash-level", size: 7, contentType: "application/octet-stream" }]
      },
      regionBundles: []
    });
    const headers = plan.nonRegionPackUpload?.fullUpload?.headers ?? {};
    const storageKey = plan.nonRegionPackUpload?.fullStorageKey ?? "";
    return { instance, world, headers, storageKey, uploaded };
  }

  function putRequest(headers: Record<string, string>) {
    return new Request("https://example.invalid/upload", {
      method: "PUT",
      headers: { "content-type": "application/octet-stream", "content-length": "7", ...headers },
      body: "payload"
    });
  }

  test("a stamp alone authorizes the PUT — no epoch/token, no coordinator", async () => {
    const { instance, world, headers, storageKey, uploaded } = await fixture({ SIGNING_SECRET: "stamp-secret" });
    const stamp = headers["x-sharedworld-blob-stamp"];
    expect(stamp).toBeDefined();

    // Without epoch/token the coordinator path would refuse outright, so
    // success here proves the stamped fast path decided alone.
    await instance.uploadStorageBlob(host, world.id, storageKey, putRequest({ "x-sharedworld-blob-stamp": stamp }));
    expect(uploaded).toEqual([storageKey]);
  });

  test("a stamp for another key falls back to the coordinator and is refused", async () => {
    const { instance, world, headers } = await fixture({ SIGNING_SECRET: "stamp-secret" });
    const stamp = headers["x-sharedworld-blob-stamp"];

    await expect(Promise.resolve().then(() => instance.uploadStorageBlob(
      host,
      world.id,
      "packs/full/other-key.pack",
      putRequest({ "x-sharedworld-blob-stamp": stamp })
    ))).rejects.toMatchObject({ status: 409, code: "host_not_active" });
  });

  test("a stale-epoch stamp falls back and is refused", async () => {
    const { instance, world, storageKey } = await fixture({ SIGNING_SECRET: "stamp-secret" });
    const staleStamp = await mintBlobStamp(
      { SIGNING_SECRET: "stamp-secret" },
      { worldId: world.id, runtimeEpoch: 99, storageKey },
      new Date()
    );

    await expect(Promise.resolve().then(() => instance.uploadStorageBlob(
      host,
      world.id,
      storageKey,
      putRequest({ "x-sharedworld-blob-stamp": staleStamp! })
    ))).rejects.toMatchObject({ status: 409, code: "host_not_active" });
  });

  test("without a signing secret, plans carry no stamp and the legacy path still works", async () => {
    const { instance, world, headers, storageKey, uploaded } = await fixture({});
    expect(headers["x-sharedworld-blob-stamp"]).toBeUndefined();

    await instance.uploadStorageBlob(host, world.id, storageKey, putRequest(headers));
    expect(uploaded).toEqual([storageKey]);
  });
});

describe("download stamp mint/verify", () => {
  const env = { SIGNING_SECRET: "current-secret" };
  const scope = { worldId: "world-1", storageKey: "packs/full/ab/abc.pack", playerUuid: "player-guest" };

  test("round-trips and is bound to world, key and player", async () => {
    const stamp = await mintDownloadStamp(env, scope, NOW);
    expect(stamp).toMatch(/^v1\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/);
    expect(await verifyDownloadStamp(env, stamp!, scope, new Date(NOW.getTime() + 1000))).toBe(true);
    expect(await verifyDownloadStamp(env, stamp!, { ...scope, worldId: "world-2" }, NOW)).toBe(false);
    expect(await verifyDownloadStamp(env, stamp!, { ...scope, storageKey: "packs/other.pack" }, NOW)).toBe(false);
    expect(await verifyDownloadStamp(env, stamp!, { ...scope, playerUuid: "player-other" }, NOW)).toBe(false);
  });

  test("expires after its own (longer) TTL", async () => {
    const stamp = await mintDownloadStamp(env, scope, NOW);
    expect(await verifyDownloadStamp(env, stamp!, scope, new Date(NOW.getTime() + DOWNLOAD_STAMP_TTL_MS - 1000))).toBe(true);
    expect(await verifyDownloadStamp(env, stamp!, scope, new Date(NOW.getTime() + DOWNLOAD_STAMP_TTL_MS + 1000))).toBe(false);
  });

  test("the two stamp kinds never verify as each other", async () => {
    const download = await mintDownloadStamp(env, scope, NOW);
    const upload = await mintBlobStamp(env, CLAIMS, NOW);
    expect(await verifyBlobStamp(env, download!, SCOPE, NOW)).toBeNull();
    expect(await verifyDownloadStamp(env, upload!, scope, NOW)).toBe(false);
  });

  test("no secret: minting is skipped and verification always fails", async () => {
    expect(await mintDownloadStamp({}, scope, NOW)).toBeNull();
    const stamp = await mintDownloadStamp(env, scope, NOW);
    expect(await verifyDownloadStamp({}, stamp!, scope, NOW)).toBe(false);
  });
});

describe("stamped relay downloads", () => {
  const owner = { playerUuid: "player-owner", playerName: "Owner" };
  const guest = { playerUuid: "player-guest", playerName: "Guest" };
  const stranger = { playerUuid: "player-stranger", playerName: "Stranger" };
  const key = "packs/full/ab/abcdef.pack";
  const bytes = new TextEncoder().encode("0123456789");

  async function fixture(env: Record<string, string>) {
    const repository = createSqliteRepository();
    const instance = createTestService(repository, createBlobSigner().signer, {
      ...env,
      BLOBS: createBlobBucket({ [key]: bytes.slice(), "icons/ab/icon.png": bytes.slice() })
    } as never);
    for (const player of [owner, guest, stranger]) {
      await repository.upsertUser({ ...player, createdAt: new Date().toISOString() });
    }
    const world = await repository.createWorld(owner, "Friends SMP", "friends-smp");
    const invite = await instance.createInvite(owner, world.id, new Date("2026-01-01T00:00:00.000Z"));
    await instance.redeemInvite(guest, { code: invite.code }, new Date("2026-01-01T00:05:00.000Z"));
    await instance.claimHost(owner, world.id, { joinTarget: "example.test:25565" }, new Date());
    const runtime = instance.realtimeLocal.runtimeRecord(world.id);
    await instance.finalizeSnapshot(owner, world.id, {
      runtimeEpoch: runtime?.runtimeEpoch,
      hostToken: runtime?.runtimeToken,
      baseSnapshotId: null,
      files: [],
      packs: [{
        packId: "non-region",
        hash: "abcdef",
        size: 10,
        storageKey: key,
        transferMode: "pack-full",
        chainDepth: 0,
        files: [{ path: "level.dat", hash: "hash-level", size: 10, contentType: "application/octet-stream" }]
      }]
    } as never);
    const plan = await instance.downloadPlan(guest, world.id, { files: [], nonRegionPack: null, regionBundles: [] });
    const step = plan.nonRegionPackDownload?.steps[0];
    expect(step?.storageKey).toBe(key);
    return { repository, instance, world, download: step!.download };
  }

  function getRequest(headers: Record<string, string>) {
    return new Request("https://example.invalid/download", { headers });
  }

  test("plans stamp download URLs; a stamp alone serves the blob after membership is gone", async () => {
    const { instance, world, download } = await fixture({ SIGNING_SECRET: "stamp-secret" });
    const stamp = download.headers["x-sharedworld-blob-stamp"];
    expect(stamp).toBeDefined();

    await instance.kickMember(owner, world.id, guest.playerUuid);
    // The coordinator path refuses a revoked member, so success here proves
    // the stamped fast path decided alone (and pins the documented
    // "may finish an in-flight download" semantic).
    const response = await instance.downloadStorageBlob(guest, world.id, key, getRequest(download.headers));
    expect(response.status).toBe(200);
    expect(await response.text()).toBe("0123456789");
    await expect(Promise.resolve().then(() => instance.downloadStorageBlob(guest, world.id, key, getRequest({}))))
      .rejects.toMatchObject({ status: 403, code: "membership_revoked" });
  });

  test("a stamp is useless to another player, for another key, or without a secret", async () => {
    const { instance, world, download } = await fixture({ SIGNING_SECRET: "stamp-secret" });
    await expect(Promise.resolve().then(() => instance.downloadStorageBlob(stranger, world.id, key, getRequest(download.headers))))
      .rejects.toMatchObject({ status: 403, code: "forbidden" });
    await instance.kickMember(owner, world.id, guest.playerUuid);
    await expect(Promise.resolve().then(() => instance.downloadStorageBlob(guest, world.id, "icons/ab/icon.png", getRequest(download.headers))))
      .rejects.toMatchObject({ status: 403, code: "membership_revoked" });
  });

  test("without a signing secret, plans carry no stamp and the coordinator path still serves members", async () => {
    const { instance, world, download } = await fixture({});
    expect(download.headers["x-sharedworld-blob-stamp"]).toBeUndefined();
    const response = await instance.downloadStorageBlob(guest, world.id, key, getRequest(download.headers));
    expect(response.status).toBe(200);
  });

  test("world summaries stamp the custom icon download for the viewer", async () => {
    const { repository, instance, world } = await fixture({ SIGNING_SECRET: "stamp-secret" });
    await repository.updateWorld(owner, world.id, {
      name: "Friends SMP",
      motdLine1: null,
      motdLine2: null,
      customIconStorageKey: "icons/ab/icon.png",
      customIconPngBase64: null,
      clearCustomIcon: false
    });
    const [summary] = (await instance.listWorlds(guest)).filter((entry) => entry.id === world.id);
    const stamp = summary?.customIconDownload?.headers["x-sharedworld-blob-stamp"];
    expect(stamp).toBeDefined();
    expect(await verifyDownloadStamp(
      { SIGNING_SECRET: "stamp-secret" },
      stamp!,
      { worldId: world.id, storageKey: "icons/ab/icon.png", playerUuid: guest.playerUuid },
      new Date()
    )).toBe(true);
  });
});
