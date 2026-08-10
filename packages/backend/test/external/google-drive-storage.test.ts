import { afterAll, beforeEach, describe, expect, test } from "bun:test";

import type { Env } from "../../src/env.ts";
import { HttpError } from "../../src/http.ts";
import type { StorageBinding } from "../../src/storage.ts";
import { GoogleDriveStorageProvider } from "../../src/storage/drive.ts";
import { createSqliteRepository } from "../support/sqlite-d1.ts";

/**
 * Characterization tests for the real Google Drive HTTP client against a local
 * fake Drive API. The fake speaks just enough of the files/upload/about
 * surface for the provider's request shapes and retry behavior to be pinned.
 */

type ScriptedReply = {
  status: number;
  body?: string;
  headers?: Record<string, string>;
};

const requests: Array<{ method: string; path: string; auth: string | null; contentType: string | null; range: string | null }> = [];
let script: ScriptedReply[] = [];
let defaultReply: () => Response = () => new Response(JSON.stringify({ id: "drive-object-1" }), { status: 200 });

const server = Bun.serve({
  port: 0,
  fetch(request) {
    const url = new URL(request.url);
    requests.push({
      method: request.method,
      path: url.pathname + url.search,
      auth: request.headers.get("authorization"),
      contentType: request.headers.get("content-type"),
      range: request.headers.get("range")
    });
    const scripted = script.shift();
    if (scripted) {
      return new Response(scripted.body ?? null, { status: scripted.status, headers: scripted.headers });
    }
    return defaultReply();
  }
});

afterAll(() => {
  server.stop(true);
});

const env: Env = {
  GOOGLE_DRIVE_API_BASE: `http://127.0.0.1:${server.port}/drive/v3`,
  // Keep exponential backoff effectively instant so retry tests stay fast.
  DRIVE_RETRY_BASE_DELAY_MS: "1",
  DRIVE_RETRY_MAX_DELAY_MS: "2",
  DRIVE_MAX_UPLOAD_STARTS_PER_SECOND: "10000"
};

let accountCounter = 0;

function freshProviderFixture() {
  const repository = createSqliteRepository();
  const provider = new GoogleDriveStorageProvider(env, repository);
  // Unique account id per fixture: the provider keeps a static per-account
  // rate limiter map, and reusing ids would leak pacing state across tests.
  accountCounter += 1;
  const accountId = `storage-test-${accountCounter}`;
  const binding: StorageBinding = { provider: "google-drive", storageAccountId: accountId };
  return {
    repository,
    provider,
    binding,
    accountId,
    async seedAccount() {
      await repository.createOrUpdateStorageAccount({
        id: accountId,
        provider: "google-drive",
        ownerPlayerUuid: "player-owner",
        externalAccountId: `external-${accountId}`,
        email: "owner@example.com",
        displayName: "Owner",
        accessToken: "valid-access-token",
        refreshToken: "refresh-token-1",
        tokenExpiresAt: new Date(Date.now() + 60 * 60_000).toISOString(),
        createdAt: "2000-01-01T00:00:00.000Z",
        updatedAt: "2000-01-01T00:00:00.000Z"
      });
    }
  };
}

beforeEach(() => {
  requests.length = 0;
  script = [];
  defaultReply = () => new Response(JSON.stringify({ id: "drive-object-1" }), { status: 200 });
});

describe("GoogleDriveStorageProvider", () => {
  test("put creates a new file via multipart upload with bearer auth and records the object", async () => {
    const fixture = freshProviderFixture();
    await fixture.seedAccount();

    await fixture.provider.put(fixture.binding, "worlds/w1/snapshot.bin", new TextEncoder().encode("payload"), "application/octet-stream");

    expect(requests).toHaveLength(1);
    expect(requests[0].method).toBe("POST");
    expect(requests[0].path).toBe("/upload/drive/v3/files?uploadType=multipart");
    expect(requests[0].auth).toBe("Bearer valid-access-token");
    expect(requests[0].contentType).toStartWith("multipart/related; boundary=");

    const object = await fixture.repository.getStorageObject("google-drive", fixture.accountId, "worlds/w1/snapshot.bin");
    expect(object?.objectId).toBe("drive-object-1");
    expect(await fixture.provider.exists(fixture.binding, "worlds/w1/snapshot.bin")).toBe(true);
  });

  test("put updates an existing object in place via media PATCH", async () => {
    const fixture = freshProviderFixture();
    await fixture.seedAccount();
    await fixture.provider.put(fixture.binding, "worlds/w1/snapshot.bin", "v1", "application/octet-stream");
    requests.length = 0;

    await fixture.provider.put(fixture.binding, "worlds/w1/snapshot.bin", "v2", "application/octet-stream");

    expect(requests).toHaveLength(1);
    expect(requests[0].method).toBe("PATCH");
    expect(requests[0].path).toBe("/upload/drive/v3/files/drive-object-1?uploadType=media");
  });

  test("put retries retryable statuses with backoff and then succeeds", async () => {
    const fixture = freshProviderFixture();
    await fixture.seedAccount();
    script = [{ status: 500 }, { status: 429 }];

    await fixture.provider.put(fixture.binding, "worlds/w1/a.bin", "data", "application/octet-stream");

    expect(requests).toHaveLength(3);
  });

  test("put gives up after five attempts when the failure persists", async () => {
    const fixture = freshProviderFixture();
    await fixture.seedAccount();
    script = Array.from({ length: 5 }, () => ({ status: 503, body: "overloaded" }));

    let caught: unknown = null;
    try {
      await fixture.provider.put(fixture.binding, "worlds/w1/a.bin", "data", "application/octet-stream");
    } catch (error) {
      caught = error;
    }
    expect(caught).toBeInstanceOf(HttpError);
    expect((caught as HttpError).code).toBe("drive_upload_failed");
    expect(requests).toHaveLength(5);
  });

  test("a rate-limit 403 is retried like a 429", async () => {
    const fixture = freshProviderFixture();
    await fixture.seedAccount();
    script = [{
      status: 403,
      body: JSON.stringify({ error: { code: 403, errors: [{ domain: "usageLimits", reason: "userRateLimitExceeded" }] } })
    }];

    await fixture.provider.put(fixture.binding, "worlds/w1/a.bin", "data", "application/octet-stream");

    expect(requests).toHaveLength(2);
  });

  test("a permanent 403 (storage quota) fails fast without burning the retry ladder", async () => {
    const fixture = freshProviderFixture();
    await fixture.seedAccount();
    script = [{
      status: 403,
      body: JSON.stringify({ error: { code: 403, errors: [{ domain: "usageLimits", reason: "storageQuotaExceeded" }] } })
    }];

    let caught: unknown = null;
    try {
      await fixture.provider.put(fixture.binding, "worlds/w1/a.bin", "data", "application/octet-stream");
    } catch (error) {
      caught = error;
    }
    expect(caught).toBeInstanceOf(HttpError);
    expect((caught as HttpError).code).toBe("drive_upload_failed");
    expect((caught as HttpError).upstreamStatus).toBe(403);
    expect(requests).toHaveLength(1);
  });

  test("a missing-consent 403 tombstones the refresh token and demands a re-link", async () => {
    // Granular consent: OAuth completed without the Drive checkbox. The link
    // looks healthy until the first real Drive write lands here; nulling the
    // refresh token flips the account to unhealthy so the wizard shows the
    // connect step again.
    const fixture = freshProviderFixture();
    await fixture.seedAccount();
    script = [{
      status: 403,
      body: JSON.stringify({
        error: {
          code: 403,
          message: "Request had insufficient authentication scopes.",
          errors: [{ domain: "global", reason: "insufficientPermissions" }]
        }
      })
    }];

    let caught: unknown = null;
    try {
      await fixture.provider.put(fixture.binding, "worlds/w1/a.bin", "data", "application/octet-stream");
    } catch (error) {
      caught = error;
    }
    expect(caught).toBeInstanceOf(HttpError);
    expect((caught as HttpError).status).toBe(401);
    expect((caught as HttpError).code).toBe("drive_reauth_required");
    expect((caught as HttpError).message).toContain("checkbox");
    expect(requests).toHaveLength(1);

    const account = await fixture.repository.getStorageAccount(fixture.accountId);
    expect(account?.refreshToken).toBeNull();
  });

  test("put does not retry a non-retryable client error", async () => {
    const fixture = freshProviderFixture();
    await fixture.seedAccount();
    script = [{ status: 400, body: "bad multipart" }];

    let caught: unknown = null;
    try {
      await fixture.provider.put(fixture.binding, "worlds/w1/a.bin", "data", "application/octet-stream");
    } catch (error) {
      caught = error;
    }
    expect(caught).toBeInstanceOf(HttpError);
    expect((caught as HttpError).code).toBe("drive_upload_failed");
    expect((caught as HttpError).message).toContain("HTTP 400");
    expect(requests).toHaveLength(1);
  });

  test("get returns the blob for a known object", async () => {
    const fixture = freshProviderFixture();
    await fixture.seedAccount();
    await fixture.provider.put(fixture.binding, "worlds/w1/a.bin", "stored-bytes", "application/octet-stream");
    requests.length = 0;
    defaultReply = () => new Response("stored-bytes", { status: 200 });

    const blob = await fixture.provider.get(fixture.binding, "worlds/w1/a.bin");

    expect(blob).not.toBeNull();
    expect(new TextDecoder().decode(await blob!.arrayBuffer())).toBe("stored-bytes");
    expect(requests[0].method).toBe("GET");
    expect(requests[0].path).toBe("/drive/v3/files/drive-object-1?alt=media");
  });

  test("a ranged get forwards Range and streams Drive's 206 through", async () => {
    const fixture = freshProviderFixture();
    await fixture.seedAccount();
    await fixture.provider.put(fixture.binding, "worlds/w1/a.bin", "stored-bytes", "application/octet-stream");
    requests.length = 0;
    script = [{ status: 206, body: "red-bytes", headers: { "content-range": "bytes 3-11/12", "content-length": "9" } }];

    const blob = await fixture.provider.get(fixture.binding, "worlds/w1/a.bin", { offset: 3, endInclusive: null });

    expect(blob).not.toBeNull();
    expect(blob!.status).toBe(206);
    expect(blob!.contentRange).toBe("bytes 3-11/12");
    expect(blob!.size).toBe(9);
    expect(requests[0].range).toBe("bytes=3-");
    expect(new TextDecoder().decode(await blob!.arrayBuffer())).toBe("red-bytes");
  });

  test("a range past the end maps Drive's 416 to range_not_satisfiable", async () => {
    const fixture = freshProviderFixture();
    await fixture.seedAccount();
    await fixture.provider.put(fixture.binding, "worlds/w1/a.bin", "data", "application/octet-stream");
    script = [{ status: 416 }];

    let caught: unknown = null;
    try {
      await fixture.provider.get(fixture.binding, "worlds/w1/a.bin", { offset: 9999, endInclusive: null });
    } catch (error) {
      caught = error;
    }
    expect(caught).toBeInstanceOf(HttpError);
    expect((caught as HttpError).status).toBe(416);
    expect((caught as HttpError).code).toBe("range_not_satisfiable");
  });

  test("get resolves before the body finishes streaming (no full buffering)", async () => {
    const fixture = freshProviderFixture();
    await fixture.seedAccount();
    await fixture.provider.put(fixture.binding, "worlds/w1/a.bin", "seed", "application/octet-stream");
    requests.length = 0;
    let releaseTail: () => void = () => {};
    const tailGate = new Promise<void>((resolve) => {
      releaseTail = resolve;
    });
    defaultReply = () => new Response(new ReadableStream({
      async start(controller) {
        controller.enqueue(new TextEncoder().encode("head-"));
        await tailGate;
        controller.enqueue(new TextEncoder().encode("tail"));
        controller.close();
      }
    }), { status: 200 });

    // The old implementation awaited the whole body before returning; with the
    // tail gated on a promise released only after get() resolves, buffering
    // would deadlock this test instead of passing.
    const blob = await fixture.provider.get(fixture.binding, "worlds/w1/a.bin");
    expect(blob).not.toBeNull();
    releaseTail();
    expect(new TextDecoder().decode(await blob!.arrayBuffer())).toBe("head-tail");
  });

  test("get drops the local object record when Drive reports 404", async () => {
    const fixture = freshProviderFixture();
    await fixture.seedAccount();
    await fixture.provider.put(fixture.binding, "worlds/w1/a.bin", "data", "application/octet-stream");
    script = [{ status: 404 }];

    expect(await fixture.provider.get(fixture.binding, "worlds/w1/a.bin")).toBeNull();
    expect(await fixture.repository.getStorageObject("google-drive", fixture.accountId, "worlds/w1/a.bin")).toBeNull();
  });

  test("createResumableSession POSTs for a new key and returns the Location verbatim", async () => {
    const fixture = freshProviderFixture();
    await fixture.seedAccount();
    requests.length = 0;
    script = [{ status: 200, headers: { location: `http://127.0.0.1:${server.port}/resumable/abc` } }];

    const sessionUrl = await (fixture.provider as unknown as {
      createResumableSession(binding: unknown, key: string, type: string, size: number): Promise<string>;
    }).createResumableSession(fixture.binding, "worlds/w1/new.bin", "application/octet-stream", 12345);

    expect(sessionUrl).toBe(`http://127.0.0.1:${server.port}/resumable/abc`);
    expect(requests[0].method).toBe("POST");
    expect(requests[0].path).toBe("/upload/drive/v3/files?uploadType=resumable");
  });

  test("createResumableSession PATCHes the existing Drive file id for a known key", async () => {
    const fixture = freshProviderFixture();
    await fixture.seedAccount();
    await fixture.provider.put(fixture.binding, "worlds/w1/a.bin", "old-bytes", "application/octet-stream");
    requests.length = 0;
    script = [{ status: 200, headers: { location: `http://127.0.0.1:${server.port}/resumable/upd` } }];

    const sessionUrl = await (fixture.provider as unknown as {
      createResumableSession(binding: unknown, key: string, type: string, size: number): Promise<string>;
    }).createResumableSession(fixture.binding, "worlds/w1/a.bin", "application/octet-stream", 999);

    expect(sessionUrl).toContain("/resumable/upd");
    expect(requests[0].method).toBe("PATCH");
    expect(requests[0].path).toBe("/upload/drive/v3/files/drive-object-1?uploadType=resumable");
  });

  test("probeResumableSession maps 308/complete/expired states", async () => {
    const fixture = freshProviderFixture();
    await fixture.seedAccount();
    const probe = (sessionPath: string) => (fixture.provider as unknown as {
      probeResumableSession(binding: unknown, url: string, size: number): Promise<unknown>;
    }).probeResumableSession(fixture.binding, `http://127.0.0.1:${server.port}${sessionPath}`, 1000);

    script = [{ status: 308, headers: { range: "bytes=0-499" } }];
    expect(await probe("/resumable/x")).toEqual({ status: "incomplete", receivedUpTo: 500 });

    script = [{ status: 200, body: JSON.stringify({ id: "file-1", size: "1000" }) }];
    expect(await probe("/resumable/x")).toEqual({ status: "complete", fileId: "file-1", size: 1000 });

    script = [{ status: 404 }];
    expect(await probe("/resumable/x")).toEqual({ status: "expired" });
  });

  test("registerUploadedObject supersedes a stale object id and deletes the old Drive file", async () => {
    const fixture = freshProviderFixture();
    await fixture.seedAccount();
    await fixture.provider.put(fixture.binding, "worlds/w1/a.bin", "old", "application/octet-stream");
    requests.length = 0;
    script = [{ status: 204 }];

    await (fixture.provider as unknown as {
      registerUploadedObject(binding: unknown, key: string, fileId: string, size: number, type: string): Promise<void>;
    }).registerUploadedObject(fixture.binding, "worlds/w1/a.bin", "drive-object-2", 42, "application/octet-stream");

    expect(requests[0].method).toBe("DELETE");
    expect(requests[0].path).toBe("/drive/v3/files/drive-object-1");
    const row = await fixture.repository.getStorageObject("google-drive", fixture.accountId, "worlds/w1/a.bin");
    expect(row?.objectId).toBe("drive-object-2");
    expect(row?.size).toBe(42);
  });

  test("delete removes the Drive file and the local record", async () => {
    const fixture = freshProviderFixture();
    await fixture.seedAccount();
    await fixture.provider.put(fixture.binding, "worlds/w1/a.bin", "data", "application/octet-stream");
    requests.length = 0;
    defaultReply = () => new Response(null, { status: 204 });

    await fixture.provider.delete(fixture.binding, "worlds/w1/a.bin");

    expect(requests[0].method).toBe("DELETE");
    expect(requests[0].path).toBe("/drive/v3/files/drive-object-1");
    expect(await fixture.repository.getStorageObject("google-drive", fixture.accountId, "worlds/w1/a.bin")).toBeNull();
  });

  test("[S4 fixed] get retries retryable statuses and then succeeds", async () => {
    const fixture = freshProviderFixture();
    await fixture.seedAccount();
    await fixture.provider.put(fixture.binding, "worlds/w1/a.bin", "data", "application/octet-stream");
    requests.length = 0;
    script = [{ status: 503 }, { status: 429 }];
    defaultReply = () => new Response("payload", { status: 200 });

    const blob = await fixture.provider.get(fixture.binding, "worlds/w1/a.bin");

    expect(blob).not.toBeNull();
    expect(new TextDecoder().decode(await blob!.arrayBuffer())).toBe("payload");
    expect(requests.length).toBe(3);
  });

  test("[S4 fixed] get gives up after four attempts when the failure persists", async () => {
    const fixture = freshProviderFixture();
    await fixture.seedAccount();
    await fixture.provider.put(fixture.binding, "worlds/w1/a.bin", "data", "application/octet-stream");
    requests.length = 0;
    defaultReply = () => new Response(null, { status: 503 });

    expect(fixture.provider.get(fixture.binding, "worlds/w1/a.bin"))
      .rejects.toMatchObject({ status: 503, code: "drive_download_failed" });
  });

  test("get does not retry a non-retryable client error", async () => {
    const fixture = freshProviderFixture();
    await fixture.seedAccount();
    await fixture.provider.put(fixture.binding, "worlds/w1/a.bin", "data", "application/octet-stream");
    requests.length = 0;
    script = [{ status: 400 }];

    await expect(fixture.provider.get(fixture.binding, "worlds/w1/a.bin"))
      .rejects.toMatchObject({ status: 400, code: "drive_download_failed" });
    expect(requests.length).toBe(1);
  });

  test("[S4 fixed] delete retries retryable statuses and then removes the local record", async () => {
    const fixture = freshProviderFixture();
    await fixture.seedAccount();
    await fixture.provider.put(fixture.binding, "worlds/w1/a.bin", "data", "application/octet-stream");
    requests.length = 0;
    script = [{ status: 503 }];
    defaultReply = () => new Response(null, { status: 204 });

    await fixture.provider.delete(fixture.binding, "worlds/w1/a.bin");

    expect(requests.length).toBe(2);
    expect(await fixture.repository.getStorageObject("google-drive", fixture.accountId, "worlds/w1/a.bin")).toBeNull();
  });

  test("[S4 fixed] a persistently failing delete keeps the local record for later GC", async () => {
    const fixture = freshProviderFixture();
    await fixture.seedAccount();
    await fixture.provider.put(fixture.binding, "worlds/w1/a.bin", "data", "application/octet-stream");
    requests.length = 0;
    defaultReply = () => new Response(null, { status: 500 });

    await expect(fixture.provider.delete(fixture.binding, "worlds/w1/a.bin"))
      .rejects.toMatchObject({ status: 500, code: "drive_delete_failed" });
    expect(await fixture.repository.getStorageObject("google-drive", fixture.accountId, "worlds/w1/a.bin")).not.toBeNull();
  });

  test("delete treats a Drive 404 as already gone and removes the local record", async () => {
    const fixture = freshProviderFixture();
    await fixture.seedAccount();
    await fixture.provider.put(fixture.binding, "worlds/w1/a.bin", "data", "application/octet-stream");
    requests.length = 0;
    script = [{ status: 404 }];

    await fixture.provider.delete(fixture.binding, "worlds/w1/a.bin");

    expect(requests.length).toBe(1);
    expect(await fixture.repository.getStorageObject("google-drive", fixture.accountId, "worlds/w1/a.bin")).toBeNull();
  });

  test("a 401 triggers one token refresh against the hard-coded Google endpoint and a retry", async () => {
    const fixture = freshProviderFixture();
    await fixture.seedAccount();
    // The refresh endpoint is hard-coded to oauth2.googleapis.com, so it can
    // only be intercepted by stubbing global fetch; everything else passes
    // through to the local fake Drive server.
    const originalFetch = globalThis.fetch;
    let refreshCalls = 0;
    const fetchStub = (async (input: Parameters<typeof fetch>[0], init?: Parameters<typeof fetch>[1]) => {
      const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
      if (url.startsWith("https://oauth2.googleapis.com/token")) {
        refreshCalls += 1;
        return new Response(JSON.stringify({ access_token: "refreshed-token", expires_in: 3600 }), { status: 200 });
      }
      return originalFetch(input, init);
    }) as typeof fetch;
    globalThis.fetch = fetchStub;
    try {
      script = [{ status: 401 }];
      await fixture.provider.put(fixture.binding, "worlds/w1/a.bin", "data", "application/octet-stream");
    } finally {
      globalThis.fetch = originalFetch;
    }

    expect(refreshCalls).toBe(1);
    expect(requests).toHaveLength(2);
    expect(requests[1].auth).toBe("Bearer refreshed-token");
    const account = await fixture.repository.getStorageAccount(fixture.accountId);
    expect(account?.accessToken).toBe("refreshed-token");
  });

  test("a rejected refresh with invalid_grant drops the stored refresh token", async () => {
    const fixture = freshProviderFixture();
    await fixture.seedAccount();
    // Expire the access token so the provider must refresh before any Drive call.
    const seeded = await fixture.repository.getStorageAccount(fixture.accountId);
    await fixture.repository.createOrUpdateStorageAccount({
      ...seeded!,
      tokenExpiresAt: new Date(Date.now() - 60_000).toISOString()
    });
    const originalFetch = globalThis.fetch;
    const fetchStub = (async (input: Parameters<typeof fetch>[0], init?: Parameters<typeof fetch>[1]) => {
      const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
      if (url.startsWith("https://oauth2.googleapis.com/token")) {
        return new Response(JSON.stringify({ error: "invalid_grant" }), { status: 400 });
      }
      return originalFetch(input, init);
    }) as typeof fetch;
    globalThis.fetch = fetchStub;
    let caught: unknown = null;
    try {
      await fixture.provider.put(fixture.binding, "worlds/w1/a.bin", "data", "application/octet-stream");
    } catch (error) {
      caught = error;
    } finally {
      globalThis.fetch = originalFetch;
    }

    expect((caught as HttpError).code).toBe("drive_reauth_required");
    const account = await fixture.repository.getStorageAccount(fixture.accountId);
    expect(account?.refreshToken).toBeNull();
    // No Drive traffic happened with a dead authorization.
    expect(requests).toHaveLength(0);
  });

  test("a rejected refresh without invalid_grant keeps the stored refresh token", async () => {
    const fixture = freshProviderFixture();
    await fixture.seedAccount();
    const seeded = await fixture.repository.getStorageAccount(fixture.accountId);
    await fixture.repository.createOrUpdateStorageAccount({
      ...seeded!,
      tokenExpiresAt: new Date(Date.now() - 60_000).toISOString()
    });
    const originalFetch = globalThis.fetch;
    const fetchStub = (async (input: Parameters<typeof fetch>[0], init?: Parameters<typeof fetch>[1]) => {
      const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
      if (url.startsWith("https://oauth2.googleapis.com/token")) {
        return new Response(null, { status: 503 });
      }
      return originalFetch(input, init);
    }) as typeof fetch;
    globalThis.fetch = fetchStub;
    let caught: unknown = null;
    try {
      await fixture.provider.put(fixture.binding, "worlds/w1/a.bin", "data", "application/octet-stream");
    } catch (error) {
      caught = error;
    } finally {
      globalThis.fetch = originalFetch;
    }

    expect((caught as HttpError).code).toBe("drive_reauth_required");
    const account = await fixture.repository.getStorageAccount(fixture.accountId);
    expect(account?.refreshToken).toBe("refresh-token-1");
  });

  test("quota parses the storageQuota payload", async () => {
    const fixture = freshProviderFixture();
    await fixture.seedAccount();
    defaultReply = () =>
      new Response(JSON.stringify({ storageQuota: { usage: "1024", limit: "2048" } }), { status: 200 });

    expect(await fixture.provider.quota(fixture.binding)).toEqual({ usedBytes: 1024, totalBytes: 2048 });
    expect(requests[0].path).toBe("/drive/v3/about?fields=storageQuota");
  });

  test("an unlinked binding is rejected before any network traffic", async () => {
    const fixture = freshProviderFixture();
    let caught: unknown = null;
    try {
      await fixture.provider.put({ provider: "google-drive", storageAccountId: null }, "k", "v", "text/plain");
    } catch (error) {
      caught = error;
    }
    expect((caught as HttpError).code).toBe("missing_storage_account");
    expect(requests).toHaveLength(0);
  });
});
