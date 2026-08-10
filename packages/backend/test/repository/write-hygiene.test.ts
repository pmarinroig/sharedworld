import { describe, expect, test } from "bun:test";

import { createSqliteRepository } from "../support/sqlite-d1.ts";

/**
 * EB8 write hygiene: no-op writes stay no-ops, and the unbounded tables
 * (auth challenges, user sessions, confirmed upload sessions) get bounded
 * opportunistic sweeps on their natural write paths — there is no cron.
 */
describe("write hygiene", () => {
  const owner = { playerUuid: "player-owner", playerName: "Owner" };

  function totalChanges(repository: ReturnType<typeof createSqliteRepository>): number {
    return Number((repository.raw.query("SELECT total_changes() AS c").get() as { c: number }).c);
  }

  test("a same-name login writes no user row", async () => {
    const repository = createSqliteRepository();
    await repository.upsertUser({ ...owner, createdAt: "2026-01-01T00:00:00.000Z" });

    const before = totalChanges(repository);
    await repository.upsertUser({ ...owner, createdAt: "2026-01-02T00:00:00.000Z" });
    expect(totalChanges(repository)).toBe(before);

    await repository.upsertUser({ playerUuid: owner.playerUuid, playerName: "Renamed", createdAt: "2026-01-03T00:00:00.000Z" });
    expect(totalChanges(repository)).toBe(before + 1);
    repository.close();
  });

  test("creating a challenge sweeps long-expired predecessors, bounded", async () => {
    const repository = createSqliteRepository();
    for (let index = 0; index < 3; index += 1) {
      repository.raw.exec(
        `INSERT INTO auth_challenges (nonce, expires_at, used_at) VALUES ('stale-${index}', '2020-01-01T00:00:00.000Z', NULL)`
      );
    }

    await repository.createChallenge({ serverId: "fresh", expiresAt: new Date(Date.now() + 300_000).toISOString(), usedAt: null });

    const remaining = repository.raw.query("SELECT nonce FROM auth_challenges ORDER BY nonce").all() as Array<{ nonce: string }>;
    expect(remaining.map((row) => row.nonce)).toEqual(["fresh"]);
    repository.close();
  });

  test("creating a session sweeps long-expired predecessors", async () => {
    const repository = createSqliteRepository();
    await repository.upsertUser({ ...owner, createdAt: "2026-01-01T00:00:00.000Z" });
    repository.raw.exec(
      `INSERT INTO user_sessions (token, player_uuid, player_name, created_at, expires_at)
       VALUES ('ancient', 'player-owner', 'Owner', '2020-01-01T00:00:00.000Z', '2020-01-08T00:00:00.000Z')`
    );

    await repository.createSession({
      token: "fresh",
      playerUuid: owner.playerUuid,
      playerName: owner.playerName,
      expiresAt: new Date(Date.now() + 168 * 60 * 60_000).toISOString()
    });

    const tokens = (repository.raw.query("SELECT token FROM user_sessions ORDER BY token").all() as Array<{ token: string }>)
      .map((row) => row.token);
    expect(tokens).toEqual(["fresh"]);
    repository.close();
  });

  test("confirmed upload sessions past the retry window are deleted; fresh and unconfirmed stay", async () => {
    const repository = createSqliteRepository();
    const record = (uploadId: string, createdAt: string, confirmedAt: string | null) => ({
      uploadId,
      provider: "google-drive" as const,
      storageAccountId: "account-1",
      worldId: "world-1",
      storageKey: `packs/${uploadId}.pack`,
      sessionUrl: `https://upload.example/${uploadId}`,
      contentType: "application/octet-stream",
      expectedSize: 100,
      createdAt,
      confirmedAt
    });
    await repository.createUploadSession(record("old-confirmed", "2026-01-01T00:00:00.000Z", null));
    await repository.markUploadSessionConfirmed("old-confirmed", "2026-01-01T01:00:00.000Z");
    await repository.createUploadSession(record("fresh-confirmed", "2026-01-05T00:00:00.000Z", null));
    await repository.markUploadSessionConfirmed("fresh-confirmed", "2026-01-05T01:00:00.000Z");
    await repository.createUploadSession(record("unconfirmed", "2026-01-01T00:00:00.000Z", null));

    await repository.deleteConfirmedUploadSessionsBefore("google-drive", "account-1", "2026-01-04T00:00:00.000Z", 20);

    expect(await repository.getUploadSession("old-confirmed")).toBeNull();
    expect(await repository.getUploadSession("fresh-confirmed")).not.toBeNull();
    expect(await repository.getUploadSession("unconfirmed")).not.toBeNull();
    repository.close();
  });
});
