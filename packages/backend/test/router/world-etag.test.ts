import { describe, expect, test } from "bun:test";

import { createRouter } from "../../src/router.ts";
import { createRouterService } from "../support/router.ts";
import { createBlobSigner, createTestService } from "../support/service-fixtures.ts";
import { createSqliteRepository } from "../support/sqlite-d1.ts";

/**
 * EB5: conditional GETs on the two world read endpoints. The token hashes
 * the underlying change facts (never the body — it is per-user and carries
 * an advisory per-call expiresAt), so a 304 skips the whole response build.
 */
describe("world route conditional GETs", () => {
  function authed(path: string, etag?: string) {
    return new Request(`http://127.0.0.1:8787${path}`, {
      headers: {
        authorization: "Bearer session-token",
        ...(etag == null ? {} : { "if-none-match": etag })
      }
    });
  }

  test("matching If-None-Match short-circuits to 304 without building the list", async () => {
    let listCalls = 0;
    const router = createRouter(createRouterService({
      async worldsEtag() {
        return 'W/"stable-token"';
      },
      async listWorlds() {
        listCalls += 1;
        return [];
      }
    }));

    const fresh = await router(authed("/worlds"));
    expect(fresh.status).toBe(200);
    expect(fresh.headers.get("etag")).toBe('W/"stable-token"');
    expect(listCalls).toBe(1);

    const cached = await router(authed("/worlds", 'W/"stable-token"'));
    expect(cached.status).toBe(304);
    expect(cached.headers.get("etag")).toBe('W/"stable-token"');
    expect(await cached.text()).toBe("");
    expect(listCalls).toBe(1);

    const changed = await router(authed("/worlds", 'W/"old-token"'));
    expect(changed.status).toBe(200);
    expect(listCalls).toBe(2);
  });

  test("a null world etag (no access) falls through to the fresh error", async () => {
    const router = createRouter(createRouterService({
      async worldEtag() {
        return null;
      },
      async getWorld() {
        const { HttpError } = await import("../../src/http.ts");
        throw new HttpError(404, "world_not_found", "SharedWorld server not found.");
      }
    }));

    const response = await router(authed("/worlds/world-1", 'W/"anything"'));
    expect(response.status).toBe(404);
  });
});

describe("world etag token sensitivity", () => {
  const owner = { playerUuid: "player-owner", playerName: "Owner" };
  const guest = { playerUuid: "player-guest", playerName: "Guest" };

  async function fixture() {
    const repository = createSqliteRepository();
    const instance = createTestService(repository, createBlobSigner().signer, {});
    await repository.upsertUser({ ...owner, createdAt: new Date().toISOString() });
    const world = await repository.createWorld(owner, "Friends SMP", "friends-smp");
    return { repository, instance, world };
  }

  test("stable across repeats, different per user, sensitive to every input class", async () => {
    const { repository, instance, world } = await fixture();

    const base = await instance.worldsEtag(owner);
    expect(await instance.worldsEtag(owner)).toBe(base);
    expect(base).toMatch(/^W\/"[0-9a-f]{64}"$/);

    // Rename
    await repository.updateWorld(owner, world.id, { name: "Renamed SMP" });
    const afterRename = await instance.worldsEtag(owner);
    expect(afterRename).not.toBe(base);

    // Settings revision
    await repository.updateWorldSettings(world.id, JSON.stringify({ difficulty: "hard" }));
    const afterSettings = await instance.worldsEtag(owner);
    expect(afterSettings).not.toBe(afterRename);

    // Membership change
    await repository.addMembership({
      worldId: world.id,
      playerUuid: guest.playerUuid,
      playerName: guest.playerName,
      role: "member",
      joinedAt: new Date().toISOString(),
      deletedAt: null,
      canUseCommands: false
    });
    const afterJoin = await instance.worldsEtag(owner);
    expect(afterJoin).not.toBe(afterSettings);

    // Snapshot finalize
    await repository.finalizeSnapshot(world.id, owner, { files: [], packs: [] }, new Date("2099-01-01T00:00:00.000Z"));
    const afterSnapshot = await instance.worldsEtag(owner);
    expect(afterSnapshot).not.toBe(afterJoin);

    // Runtime mirror change
    await repository.upsertRuntimeMirror(world.id, JSON.stringify({ phase: "host-live" }), null);
    const afterMirror = await instance.worldsEtag(owner);
    expect(afterMirror).not.toBe(afterSnapshot);

    // Per-user: same world set, different caller (guest is now a member).
    expect(await instance.worldsEtag(guest)).not.toBe(afterMirror);
  });

  test("invite state moves only the owner's world token, including pure time expiry", async () => {
    const { instance, world } = await fixture();

    const before = await instance.worldEtag(owner, world.id, new Date("2026-01-01T00:00:00.000Z"));
    await instance.createInvite(owner, world.id, new Date("2026-01-01T00:00:00.000Z"));
    const withInvite = await instance.worldEtag(owner, world.id, new Date("2026-01-01T00:30:00.000Z"));
    expect(withInvite).not.toBe(before);

    // No data changed — only time passed beyond the invite's expiry.
    const afterExpiry = await instance.worldEtag(owner, world.id, new Date("2027-01-01T00:00:00.000Z"));
    expect(afterExpiry).not.toBe(withInvite);
  });

  test("no access yields a null world token", async () => {
    const { instance, world } = await fixture();
    expect(await instance.worldEtag({ playerUuid: "player-outsider", playerName: "Outsider" }, world.id)).toBeNull();
    expect(await instance.worldEtag(owner, "world_missing")).toBeNull();
  });
});
