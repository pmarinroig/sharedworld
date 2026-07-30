import { afterAll, beforeEach, describe, expect, test } from "bun:test";

import type { Env } from "../../src/env.ts";
import { HttpError } from "../../src/http.ts";
import { GoogleDriveStorageProvider, type StorageBinding } from "../../src/storage.ts";
import { createSqliteRepository } from "../support/sqlite-d1.ts";

/**
 * Characterization tests for the real Google Drive HTTP client against a local
 * fake Drive API. The fake speaks just enough of the files/upload/about
 * surface for the provider's request shapes and retry behavior to be pinned.
 */

type ScriptedReply = {
  status: number;
  body?: string;
};

const requests: Array<{ method: string; path: string; auth: string | null; contentType: string | null }> = [];
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
      contentType: request.headers.get("content-type")
    });
    const scripted = script.shift();
    if (scripted) {
      return new Response(scripted.body ?? null, { status: scripted.status });
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

  test("get drops the local object record when Drive reports 404", async () => {
    const fixture = freshProviderFixture();
    await fixture.seedAccount();
    await fixture.provider.put(fixture.binding, "worlds/w1/a.bin", "data", "application/octet-stream");
    script = [{ status: 404 }];

    expect(await fixture.provider.get(fixture.binding, "worlds/w1/a.bin")).toBeNull();
    expect(await fixture.repository.getStorageObject("google-drive", fixture.accountId, "worlds/w1/a.bin")).toBeNull();
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
