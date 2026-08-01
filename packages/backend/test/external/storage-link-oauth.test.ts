import { afterEach, describe, expect, test } from "bun:test";

import type { Env } from "../../src/env.ts";
import { HttpError } from "../../src/http.ts";
import { StorageLinkDomainService } from "../../src/storage/link-service.ts";
import { createSqliteRepository } from "../support/sqlite-d1.ts";

/**
 * Characterization tests for the real Google OAuth exchange. Both endpoints
 * (token exchange and userinfo) are hard-coded in link-service.ts rather than
 * env-overridable, so these tests stub global fetch by URL. Making those
 * endpoints injectable would be a desirable future product change.
 */

const env: Env = {
  GOOGLE_OAUTH_CLIENT_ID: "client-id-1",
  GOOGLE_OAUTH_CLIENT_SECRET: "client-secret-1",
  PUBLIC_BASE_URL: "https://backend.example"
};

const ctx = { playerUuid: "player-owner", playerName: "Owner", requestOrigin: "https://backend.example" };

const originalFetch = globalThis.fetch;

afterEach(() => {
  globalThis.fetch = originalFetch;
});

type FetchScript = {
  token?: () => Response;
  userinfo?: () => Response;
};

function stubGoogleFetch(script: FetchScript): { tokenRequests: URLSearchParams[] } {
  const seen = { tokenRequests: [] as URLSearchParams[] };
  const fetchStub = (async (input: Parameters<typeof fetch>[0], init?: Parameters<typeof fetch>[1]) => {
    const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
    if (url.startsWith("https://oauth2.googleapis.com/token")) {
      seen.tokenRequests.push(new URLSearchParams(String(init?.body)));
      return script.token?.() ?? new Response(null, { status: 500 });
    }
    if (url.startsWith("https://openidconnect.googleapis.com/v1/userinfo")) {
      return script.userinfo?.() ?? new Response(null, { status: 500 });
    }
    throw new Error(`Unexpected fetch in test: ${url}`);
  }) as typeof fetch;
  globalThis.fetch = fetchStub;
  return seen;
}

async function createPendingSession(service: StorageLinkDomainService, repository: ReturnType<typeof createSqliteRepository>) {
  const session = await service.createStorageLink(ctx, {}, new Date());
  const record = await repository.getStorageLinkSession(session.id);
  return { sessionId: session.id, state: `${session.id}:${record!.state}` };
}

function freshService() {
  const repository = createSqliteRepository();
  return { repository, service: new StorageLinkDomainService(repository, env, "google-drive") };
}

describe("StorageLinkDomainService Google OAuth exchange", () => {
  test("happy path links the session and stores the account tokens", async () => {
    const { repository, service } = freshService();
    const { sessionId, state } = await createPendingSession(service, repository);
    const seen = stubGoogleFetch({
      token: () =>
        new Response(JSON.stringify({ access_token: "at-1", refresh_token: "rt-1", expires_in: 3600 }), { status: 200 }),
      userinfo: () =>
        new Response(JSON.stringify({ sub: "google-sub-1", email: "owner@gmail.com", name: "Owner" }), { status: 200 })
    });

    const linked = await service.completeStorageLink(sessionId, { sessionId, code: "auth-code-1", state }, new Date());

    expect(linked.status).toBe("linked");
    expect(linked.linkedAccountEmail).toBe("owner@gmail.com");
    expect(seen.tokenRequests[0].get("code")).toBe("auth-code-1");
    expect(seen.tokenRequests[0].get("grant_type")).toBe("authorization_code");
    const account = await repository.findStorageAccountByExternalId("google-drive", "google-sub-1");
    expect(account?.accessToken).toBe("at-1");
    expect(account?.refreshToken).toBe("rt-1");
  });

  test("a grant whose scope field includes drive.appdata links normally", async () => {
    const { repository, service } = freshService();
    const { sessionId, state } = await createPendingSession(service, repository);
    stubGoogleFetch({
      token: () =>
        new Response(JSON.stringify({
          access_token: "at-1",
          refresh_token: "rt-1",
          expires_in: 3600,
          scope: "openid https://www.googleapis.com/auth/userinfo.email https://www.googleapis.com/auth/drive.appdata"
        }), { status: 200 }),
      userinfo: () =>
        new Response(JSON.stringify({ sub: "google-sub-1", email: "owner@gmail.com", name: "Owner" }), { status: 200 })
    });

    const linked = await service.completeStorageLink(sessionId, { sessionId, code: "auth-code-1", state }, new Date());
    expect(linked.status).toBe("linked");
  });

  test("a grant missing the Drive scope fails the session and asks for the checkbox", async () => {
    // Google's granular consent lets the user finish OAuth with the Drive
    // permission checkbox unticked: refresh token valid, link "healthy",
    // every Drive write 403s. Catch it at the callback instead.
    const { repository, service } = freshService();
    const { sessionId, state } = await createPendingSession(service, repository);
    stubGoogleFetch({
      token: () =>
        new Response(JSON.stringify({
          access_token: "at-1",
          refresh_token: "rt-1",
          expires_in: 3600,
          scope: "openid https://www.googleapis.com/auth/userinfo.email"
        }), { status: 200 }),
      userinfo: () =>
        new Response(JSON.stringify({ sub: "google-sub-1", email: "owner@gmail.com", name: "Owner" }), { status: 200 })
    });

    let caught: unknown = null;
    try {
      await service.completeStorageLink(sessionId, { sessionId, code: "auth-code-1", state }, new Date());
    } catch (error) {
      caught = error;
    }
    expect(caught).toBeInstanceOf(HttpError);
    expect((caught as HttpError).status).toBe(409);
    expect((caught as HttpError).code).toBe("storage_link_needs_consent");
    expect((caught as HttpError).message).toContain("checkbox");

    const session = await repository.getStorageLinkSession(sessionId);
    expect(session?.status).toBe("failed");
    expect(session?.errorMessage).toContain("checkbox");
    // The useless grant is never stored as an account.
    expect(await repository.findStorageAccountByExternalId("google-drive", "google-sub-1")).toBeNull();
  });

  test("a failed token exchange maps to oauth_exchange_failed", async () => {
    const { repository, service } = freshService();
    const { sessionId, state } = await createPendingSession(service, repository);
    stubGoogleFetch({ token: () => new Response("denied", { status: 400 }) });

    let caught: unknown = null;
    try {
      await service.completeStorageLink(sessionId, { sessionId, code: "bad-code", state }, new Date());
    } catch (error) {
      caught = error;
    }
    expect(caught).toBeInstanceOf(HttpError);
    expect((caught as HttpError).status).toBe(401);
    expect((caught as HttpError).code).toBe("oauth_exchange_failed");
  });

  test("a failed userinfo lookup maps to oauth_profile_failed", async () => {
    const { repository, service } = freshService();
    const { sessionId, state } = await createPendingSession(service, repository);
    stubGoogleFetch({
      token: () => new Response(JSON.stringify({ access_token: "at-1", expires_in: 3600 }), { status: 200 }),
      userinfo: () => new Response(null, { status: 403 })
    });

    let caught: unknown = null;
    try {
      await service.completeStorageLink(sessionId, { sessionId, code: "auth-code-1", state }, new Date());
    } catch (error) {
      caught = error;
    }
    expect((caught as HttpError).code).toBe("oauth_profile_failed");
  });

  test("a malformed token-endpoint body maps to oauth_exchange_failed", async () => {
    const { repository, service } = freshService();
    const { sessionId, state } = await createPendingSession(service, repository);
    stubGoogleFetch({ token: () => new Response("<html>not json</html>", { status: 200 }) });

    let caught: unknown = null;
    try {
      await service.completeStorageLink(sessionId, { sessionId, code: "auth-code-1", state }, new Date());
    } catch (error) {
      caught = error;
    }
    expect(caught).toBeInstanceOf(HttpError);
    expect((caught as HttpError).status).toBe(401);
    expect((caught as HttpError).code).toBe("oauth_exchange_failed");
  });

  test("a malformed userinfo body maps to oauth_profile_failed", async () => {
    const { repository, service } = freshService();
    const { sessionId, state } = await createPendingSession(service, repository);
    stubGoogleFetch({
      token: () => new Response(JSON.stringify({ access_token: "at-1", expires_in: 3600 }), { status: 200 }),
      userinfo: () => new Response("<html>not json</html>", { status: 200 })
    });

    let caught: unknown = null;
    try {
      await service.completeStorageLink(sessionId, { sessionId, code: "auth-code-1", state }, new Date());
    } catch (error) {
      caught = error;
    }
    expect((caught as HttpError).code).toBe("oauth_profile_failed");
  });

  test("a missing callback code is rejected up front", async () => {
    const { repository, service } = freshService();
    const { sessionId, state } = await createPendingSession(service, repository);
    stubGoogleFetch({});

    let caught: unknown = null;
    try {
      await service.completeStorageLink(sessionId, { sessionId, state }, new Date());
    } catch (error) {
      caught = error;
    }
    expect((caught as HttpError).code).toBe("missing_oauth_code");
  });

  test("an expired session is rejected before any Google traffic", async () => {
    const { service } = freshService();
    const created = new Date("2026-01-01T00:00:00.000Z");
    const session = await service.createStorageLink(ctx, {}, created);
    stubGoogleFetch({}); // any fetch would throw "Unexpected fetch"

    let caught: unknown = null;
    try {
      await service.completeStorageLink(
        session.id,
        { sessionId: session.id, code: "auth-code-1" },
        new Date("2026-01-01T01:00:00.000Z")
      );
    } catch (error) {
      caught = error;
    }
    expect((caught as HttpError).status).toBe(410);
    expect((caught as HttpError).code).toBe("storage_link_expired");
  });

  test("a grant without a refresh token for an unknown account fails the session with a consent retry", async () => {
    const { repository, service } = freshService();
    const { sessionId, state } = await createPendingSession(service, repository);
    stubGoogleFetch({
      token: () => new Response(JSON.stringify({ access_token: "at-1", expires_in: 3600 }), { status: 200 }),
      userinfo: () => new Response(JSON.stringify({ sub: "google-sub-new", email: "owner@gmail.com" }), { status: 200 })
    });

    let caught: unknown = null;
    try {
      await service.completeStorageLink(sessionId, { sessionId, code: "auth-code-1", state }, new Date());
    } catch (error) {
      caught = error;
    }
    expect(caught).toBeInstanceOf(HttpError);
    expect((caught as HttpError).status).toBe(409);
    expect((caught as HttpError).code).toBe("storage_link_needs_consent");
    const session = await repository.getStorageLinkSession(sessionId);
    expect(session?.status).toBe("failed");
    expect(session?.errorMessage).toContain("try connecting again");
  });

  test("a grant without a refresh token still links a known account that has one stored", async () => {
    const { repository, service } = freshService();
    await repository.createOrUpdateStorageAccount({
      id: "storage-1",
      provider: "google-drive",
      ownerPlayerUuid: ctx.playerUuid,
      externalAccountId: "google-sub-1",
      email: "owner@gmail.com",
      displayName: "Owner",
      accessToken: "old-at",
      refreshToken: "stored-rt",
      tokenExpiresAt: "2026-01-01T00:00:00.000Z",
      createdAt: "2026-01-01T00:00:00.000Z",
      updatedAt: "2026-01-01T00:00:00.000Z"
    });
    const { sessionId, state } = await createPendingSession(service, repository);
    stubGoogleFetch({
      token: () => new Response(JSON.stringify({ access_token: "at-2", expires_in: 3600 }), { status: 200 }),
      userinfo: () => new Response(JSON.stringify({ sub: "google-sub-1", email: "owner@gmail.com" }), { status: 200 })
    });

    const linked = await service.completeStorageLink(sessionId, { sessionId, code: "auth-code-1", state }, new Date());

    expect(linked.status).toBe("linked");
    const account = await repository.findStorageAccountByExternalId("google-drive", "google-sub-1");
    expect(account?.accessToken).toBe("at-2");
    expect(account?.refreshToken).toBe("stored-rt");
  });

  test("[S3 fixed] a mismatched state is rejected before any token exchange", async () => {
    const { repository, service } = freshService();
    const { sessionId } = await createPendingSession(service, repository);
    stubGoogleFetch({}); // any Google fetch would throw via the 500 default

    expect(service.completeStorageLink(sessionId, { sessionId, code: "auth-code-1", state: `${sessionId}:wrong-nonce` }, new Date()))
      .rejects.toMatchObject({ status: 403, code: "storage_link_state_mismatch" });
    const session = await repository.getStorageLinkSession(sessionId);
    expect(session?.status).toBe("pending");
  });

  test("[S3 fixed] a missing state is rejected", async () => {
    const { repository, service } = freshService();
    const { sessionId } = await createPendingSession(service, repository);
    expect(service.completeStorageLink(sessionId, { sessionId, code: "auth-code-1" }, new Date()))
      .rejects.toMatchObject({ status: 403, code: "storage_link_state_mismatch" });
  });

  test("the dev-mock bare nonce form of state is accepted", async () => {
    const { repository, service } = freshService();
    const { sessionId, state } = await createPendingSession(service, repository);
    const bareNonce = state.slice(sessionId.length + 1);
    stubGoogleFetch({
      token: () => new Response(JSON.stringify({ access_token: "at-1", refresh_token: "rt-1", expires_in: 3600 }), { status: 200 }),
      userinfo: () => new Response(JSON.stringify({ sub: "google-sub-9", email: "owner@gmail.com" }), { status: 200 })
    });
    const linked = await service.completeStorageLink(sessionId, { sessionId, code: "auth-code-1", state: bareNonce }, new Date());
    expect(linked.status).toBe("linked");
  });
});
