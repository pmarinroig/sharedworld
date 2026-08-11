import { describe, expect, test } from "bun:test";

import { HttpError } from "../../src/http.ts";
import type { SharedWorldRepository } from "../../src/repository.ts";
import type { ResumableProbe, ResumableUploadCapable, StorageBinding, StorageProvider } from "../../src/storage.ts";
import { createSqliteRepository } from "../support/sqlite-d1.ts";
import { createBlobSigner, createTestService } from "../support/service-fixtures.ts";

const OWNER = { playerUuid: "player-owner", playerName: "Owner" };
const KEY = "packs/full/aa/aaaa.pack";

type FakeSession = {
  storageKey: string;
  state: "incomplete" | "complete" | "expired";
  received: number;
  fileId: string | null;
  size: number | null;
};

function fakeResumableProvider(repository: SharedWorldRepository) {
  const sessions = new Map<string, FakeSession>();
  const deletedFileIds: string[] = [];
  let counter = 0;
  const provider: StorageProvider & ResumableUploadCapable & {
    sessions: Map<string, FakeSession>;
    deletedFileIds: string[];
    completeSession(url: string, fileId: string, size: number): void;
    expireSession(url: string): void;
  } = {
    provider: "google-drive",
    async exists() {
      return false;
    },
    async put() {},
    async get() {
      return null;
    },
    async delete() {},
    async quota() {
      return { usedBytes: null, totalBytes: null };
    },
    async createResumableSession(_binding, storageKey) {
      const url = `https://drive.invalid/session/${++counter}`;
      sessions.set(url, { storageKey, state: "incomplete", received: 0, fileId: null, size: null });
      return url;
    },
    async probeResumableSession(_binding, sessionUrl): Promise<ResumableProbe> {
      const session = sessions.get(sessionUrl);
      if (!session || session.state === "expired") {
        return { status: "expired" };
      }
      if (session.state === "incomplete") {
        return { status: "incomplete", receivedUpTo: session.received };
      }
      return { status: "complete", fileId: session.fileId as string, size: session.size as number };
    },
    async registerUploadedObject(binding: StorageBinding, storageKey, fileId, size, contentType) {
      await repository.upsertStorageObject({
        provider: "google-drive",
        storageAccountId: binding.storageAccountId as string,
        storageKey,
        objectId: fileId,
        contentType,
        size,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString()
      });
    },
    async deleteObjectById(_binding, fileId) {
      deletedFileIds.push(fileId);
    },
    sessions,
    deletedFileIds,
    completeSession(url, fileId, size) {
      const session = sessions.get(url);
      if (session) {
        session.state = "complete";
        session.fileId = fileId;
        session.size = size;
      }
    },
    expireSession(url) {
      const session = sessions.get(url);
      if (session) {
        session.state = "expired";
      }
    }
  };
  return provider;
}

async function fixture() {
  const repository = createSqliteRepository();
  const provider = fakeResumableProvider(repository);
  const instance = createTestService(repository, createBlobSigner().signer, provider, {});
  await repository.upsertUser({ ...OWNER, createdAt: new Date().toISOString() });
  const world = await repository.createWorld(OWNER, "Friends SMP", "friends-smp",
    { provider: "google-drive", storageAccountId: "storage-account-1" });
  await instance.claimHost(OWNER, world.id, { joinTarget: "example.test:25565" }, new Date());
  const plan = await instance.prepareUploads(OWNER, world.id, { files: [], nonRegionPack: null, regionBundles: [] });
  const headers = plan.nonRegionPackUpload?.fullUpload?.headers
    ?? { "x-sharedworld-runtime-epoch": "1", "x-sharedworld-host-token": "" };
  // Authority travels in the request body for session calls; recover the
  // live tuple the same way the client would, from any signed upload slot.
  const authority = await (async () => {
    const probePlan = await instance.prepareUploads(OWNER, world.id, {
      files: [],
      nonRegionPack: { packId: "non-region", hash: "h1", size: 4, fileCount: 1, files: [{ path: "level.dat", hash: "h1-f", size: 4, contentType: "application/octet-stream" }] },
      regionBundles: []
    });
    const slot = probePlan.nonRegionPackUpload?.fullUpload?.headers ?? headers;
    return {
      runtimeEpoch: Number(slot["x-sharedworld-runtime-epoch"]),
      hostToken: slot["x-sharedworld-host-token"]
    };
  })();
  return { repository, provider, instance, worldId: world.id, authority, directUpload: plan.directUpload ?? null };
}

async function expectError(promise: Promise<unknown>, status: number, code: string): Promise<void> {
  let caught: unknown = null;
  try {
    await promise;
  } catch (error) {
    caught = error;
  }
  expect(caught).toBeInstanceOf(HttpError);
  expect((caught as HttpError).status).toBe(status);
  expect((caught as HttpError).code).toBe(code);
}

describe("direct upload sessions", () => {
  test("prepare advertises directUpload for a resumable-capable linked world", async () => {
    const { directUpload } = await fixture();
    expect(directUpload).not.toBeNull();
    expect(directUpload!.chunkSizeBytes % (256 * 1024)).toBe(0);
    expect(directUpload!.maxUploadBytes).toBeNull();
  });

  test("session then commit registers the object from provider-reported facts, idempotently", async () => {
    const { repository, provider, instance, worldId, authority } = await fixture();
    const session = await instance.createBlobUploadSession(OWNER, worldId, {
      storageKey: KEY, ...authority, contentType: "application/octet-stream", contentLength: 1000
    });
    expect(session.sessionUrl).toContain("drive.invalid/session/");

    provider.completeSession(session.sessionUrl, "file-123", 1000);
    const committed = await instance.commitBlobUploadSession(OWNER, worldId, { uploadId: session.uploadId, ...authority });
    expect(committed).toEqual({ storageKey: KEY, size: 1000 });
    const row = await repository.getStorageObject("google-drive", "storage-account-1", KEY);
    expect(row?.objectId).toBe("file-123");
    expect(row?.size).toBe(1000);

    const again = await instance.commitBlobUploadSession(OWNER, worldId, { uploadId: session.uploadId, ...authority });
    expect(again).toEqual({ storageKey: KEY, size: 1000 });
  });

  test("commit while the provider still misses bytes is a retryable 409", async () => {
    const { instance, worldId, authority } = await fixture();
    const session = await instance.createBlobUploadSession(OWNER, worldId, {
      storageKey: KEY, ...authority, contentType: "application/octet-stream", contentLength: 1000
    });
    await expectError(
      instance.commitBlobUploadSession(OWNER, worldId, { uploadId: session.uploadId, ...authority }),
      409,
      "upload_incomplete"
    );
  });

  test("commit against an expired session is 410 and forgets the session", async () => {
    const { repository, provider, instance, worldId, authority } = await fixture();
    const session = await instance.createBlobUploadSession(OWNER, worldId, {
      storageKey: KEY, ...authority, contentType: "application/octet-stream", contentLength: 1000
    });
    provider.expireSession(session.sessionUrl);
    await expectError(
      instance.commitBlobUploadSession(OWNER, worldId, { uploadId: session.uploadId, ...authority }),
      410,
      "upload_session_expired"
    );
    expect(await repository.getUploadSession(session.uploadId)).toBeNull();
  });

  test("a size mismatch deletes the stored object and fails the commit", async () => {
    const { provider, instance, worldId, authority } = await fixture();
    const session = await instance.createBlobUploadSession(OWNER, worldId, {
      storageKey: KEY, ...authority, contentType: "application/octet-stream", contentLength: 1000
    });
    provider.completeSession(session.sessionUrl, "file-bad", 999);
    await expectError(
      instance.commitBlobUploadSession(OWNER, worldId, { uploadId: session.uploadId, ...authority }),
      409,
      "upload_size_mismatch"
    );
    expect(provider.deletedFileIds).toEqual(["file-bad"]);
  });

  test("stale authority cannot open or commit sessions", async () => {
    const { instance, worldId, authority } = await fixture();
    await expectError(
      instance.createBlobUploadSession(OWNER, worldId, {
        storageKey: KEY, runtimeEpoch: authority.runtimeEpoch + 1, hostToken: "wrong",
        contentType: "application/octet-stream", contentLength: 10
      }),
      409,
      "host_not_active"
    );
  });

  test("session init sweeps stale unconfirmed sessions for the account", async () => {
    const { repository, provider, instance, worldId, authority } = await fixture();
    // A completed-but-never-confirmed session left a Drive file behind.
    const staleUrl = await provider.createResumableSession(
      { provider: "google-drive", storageAccountId: "storage-account-1" }, "packs/full/zz/old.pack", "application/octet-stream", 50);
    provider.completeSession(staleUrl, "orphan-file", 50);
    await repository.createUploadSession({
      uploadId: "upl_stale", provider: "google-drive", storageAccountId: "storage-account-1",
      worldId, storageKey: "packs/full/zz/old.pack", sessionUrl: staleUrl,
      contentType: "application/octet-stream", expectedSize: 50,
      createdAt: "2020-01-01T00:00:00.000Z", confirmedAt: null
    });

    await instance.createBlobUploadSession(OWNER, worldId, {
      storageKey: KEY, ...authority, contentType: "application/octet-stream", contentLength: 10
    });

    expect(await repository.getUploadSession("upl_stale")).toBeNull();
    expect(provider.deletedFileIds).toEqual(["orphan-file"]);
  });
});
