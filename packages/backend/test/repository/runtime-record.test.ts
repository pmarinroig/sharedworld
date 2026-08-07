import { describe, expect, test } from "bun:test";

import { createSqliteRepository } from "../support/sqlite-d1.ts";

/**
 * Characterization of repository-level runtime and storage-link-session write
 * semantics ahead of the Phase 1 fixes. Tests marked CURRENT-BUG pin behavior
 * that is scheduled to change; each carries the intended post-fix expectation
 * in a comment so the flip is an enumerated edit, not a surprise.
 */
describe("storage link session update semantics", () => {
  const NOW = new Date("2099-01-01T12:00:00.000Z");

  async function setupSession() {
    const repository = createSqliteRepository();
    await repository.createStorageLinkSession({
      id: "sess-1",
      playerUuid: "player-owner",
      provider: "google-drive",
      status: "pending",
      authUrl: "https://accounts.google.com/o/oauth2/auth?x=1",
      state: "sess-1:nonce",
      linkedAccountEmail: null,
      accountDisplayName: null,
      storageAccountId: null,
      errorMessage: null,
      createdAt: NOW.toISOString(),
      expiresAt: new Date(NOW.getTime() + 600_000).toISOString(),
      completedAt: null
    });
    return repository;
  }

  test("updates overwrite provided fields", async () => {
    const repository = await setupSession();
    await repository.updateStorageLinkSession("sess-1", { status: "failed", errorMessage: "boom" });
    const session = await repository.getStorageLinkSession("sess-1");
    expect(session?.status).toBe("failed");
    expect(session?.errorMessage).toBe("boom");
  });

  test("[S2 fixed] errorMessage null clears a previous failure message", async () => {
    const repository = await setupSession();
    await repository.updateStorageLinkSession("sess-1", { status: "failed", errorMessage: "boom" });
    await repository.updateStorageLinkSession("sess-1", {
      status: "linked",
      linkedAccountEmail: "kid@example.com",
      errorMessage: null,
      completedAt: NOW.toISOString()
    });
    const session = await repository.getStorageLinkSession("sess-1");
    expect(session?.status).toBe("linked");
    expect(session?.errorMessage).toBeNull();
    expect(session?.linkedAccountEmail).toBe("kid@example.com");
  });

  test("omitted fields keep their current values", async () => {
    const repository = await setupSession();
    await repository.updateStorageLinkSession("sess-1", { status: "failed", errorMessage: "boom" });
    await repository.updateStorageLinkSession("sess-1", { status: "pending" });
    const session = await repository.getStorageLinkSession("sess-1");
    expect(session?.status).toBe("pending");
    // errorMessage was not present in the update, so it is preserved.
    expect(session?.errorMessage).toBe("boom");
  });
});
