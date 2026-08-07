import { describe, expect, test } from "bun:test";

import { createSqliteRepository } from "../support/sqlite-d1.ts";
import { authVerifier, createBlobSigner, createTestService } from "../support/service-fixtures.ts";

const owner = { playerUuid: "player-owner", playerName: "Owner" };
const guest = { playerUuid: "player-guest", playerName: "Guest" };

async function worldWithGuestMember(repository: ReturnType<typeof createSqliteRepository>, instance: ReturnType<typeof createTestService>) {
  await repository.upsertUser({ playerUuid: owner.playerUuid, playerName: owner.playerName, createdAt: new Date().toISOString() });
  const world = await repository.createWorld(owner, "Friends SMP", "friends-smp");
  const invite = await instance.createInvite(owner, world.id, new Date("2026-01-01T00:00:00.000Z"));
  await instance.redeemInvite(guest, { code: invite.code }, new Date("2026-01-01T00:05:00.000Z"));
  return world;
}

/** Enter as `player` and heartbeat once so the runtime reaches host-live. */
async function becomeLiveHost(
  instance: ReturnType<typeof createTestService>,
  player: { playerUuid: string; playerName: string },
  worldId: string,
  at: Date
) {
  const entered = await instance.enterSession(player, worldId, {}, at);
  expect(entered.action).toBe("host");
  const assignment = entered.assignment!;
  await instance.heartbeatHost(
    player,
    worldId,
    { runtimeEpoch: assignment.runtimeEpoch, hostToken: assignment.hostToken, joinTarget: "join.example" },
    new Date(at.getTime() + 5_000)
  );
  return assignment;
}

describe("reportHostGameRules", () => {
  test("a live non-owner host persists gamerules; difficulty and game mode survive the merge", async () => {
    const repository = createSqliteRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    const world = await worldWithGuestMember(repository, instance);
    await instance.updateWorldSettings(owner, world.id, {
      settings: { difficulty: "hard", defaultGameMode: "survival", gamerules: { keepInventory: false } }
    });

    // The guest hosts: exactly the case requireOwner used to make impossible.
    const assignment = await becomeLiveHost(instance, guest, world.id, new Date("2026-01-03T00:00:00.000Z"));
    const reported = await instance.reportHostGameRules(
      guest,
      world.id,
      {
        runtimeEpoch: assignment.runtimeEpoch,
        hostToken: assignment.hostToken,
        gamerules: { keepInventory: true, mobGriefing: false }
      },
      new Date("2026-01-03T00:00:30.000Z")
    );

    expect(reported.settings).toEqual({
      difficulty: "hard",
      defaultGameMode: "survival",
      gamerules: { keepInventory: true, mobGriefing: false }
    });
    expect(reported.settingsRevision).toBe(2);

    // The next heartbeat hands the host-reported values straight back.
    const heartbeat = await instance.heartbeatHost(
      guest,
      world.id,
      { runtimeEpoch: assignment.runtimeEpoch, hostToken: assignment.hostToken, joinTarget: "join.example" },
      new Date("2026-01-03T00:00:40.000Z")
    );
    expect(heartbeat.settings).toEqual(reported.settings);
    expect(heartbeat.settingsRevision).toBe(2);

    // Last: getWorld reconciles the runtime against the wall clock, which
    // under this test's frozen timestamps retires the lease as expired.
    const reloaded = await instance.getWorld(owner, world.id, new Date("2026-01-03T00:00:50.000Z"));
    expect(reloaded.settings).toEqual(reported.settings);
    expect(reloaded.settingsRevision).toBe(2);
  });

  test("a host-reported difficulty persists alongside gamerules (in-game /difficulty)", async () => {
    const repository = createSqliteRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    const world = await worldWithGuestMember(repository, instance);
    await instance.updateWorldSettings(owner, world.id, {
      settings: { difficulty: "easy", defaultGameMode: "survival", gamerules: { keepInventory: false } }
    });

    const assignment = await becomeLiveHost(instance, guest, world.id, new Date("2026-01-03T00:00:00.000Z"));
    const reported = await instance.reportHostGameRules(
      guest,
      world.id,
      {
        runtimeEpoch: assignment.runtimeEpoch,
        hostToken: assignment.hostToken,
        gamerules: {},
        difficulty: "hard"
      },
      new Date("2026-01-03T00:00:30.000Z")
    );

    expect(reported.settings.difficulty).toBe("hard");
    expect(reported.settings.defaultGameMode).toBe("survival");
    expect(reported.settings.gamerules).toEqual({ keepInventory: false });
  });

  test("a host-reported game mode persists like difficulty does", async () => {
    const repository = createSqliteRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    const world = await worldWithGuestMember(repository, instance);
    await instance.updateWorldSettings(owner, world.id, {
      settings: { difficulty: "easy", defaultGameMode: "survival" }
    });

    const assignment = await becomeLiveHost(instance, owner, world.id, new Date("2026-01-03T00:00:00.000Z"));
    const reported = await instance.reportHostGameRules(
      owner,
      world.id,
      { runtimeEpoch: assignment.runtimeEpoch, hostToken: assignment.hostToken, gamerules: {}, defaultGameMode: "creative" },
      new Date("2026-01-03T00:00:30.000Z")
    );

    expect(reported.settings.defaultGameMode).toBe("creative");
    expect(reported.settings.difficulty).toBe("easy");

    await expect(
      instance.reportHostGameRules(
        owner,
        world.id,
        { runtimeEpoch: assignment.runtimeEpoch, hostToken: assignment.hostToken, gamerules: {}, defaultGameMode: "spectator" as never },
        new Date("2026-01-03T00:00:31.000Z")
      )
    ).rejects.toMatchObject({ code: "invalid_world_settings" });
  });

  test("an absent difficulty leaves the stored difficulty untouched", async () => {
    const repository = createSqliteRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    const world = await worldWithGuestMember(repository, instance);
    await instance.updateWorldSettings(owner, world.id, { settings: { difficulty: "peaceful" } });

    const assignment = await becomeLiveHost(instance, owner, world.id, new Date("2026-01-03T00:00:00.000Z"));
    const reported = await instance.reportHostGameRules(
      owner,
      world.id,
      { runtimeEpoch: assignment.runtimeEpoch, hostToken: assignment.hostToken, gamerules: { pvp: true } },
      new Date("2026-01-03T00:00:30.000Z")
    );

    expect(reported.settings.difficulty).toBe("peaceful");
  });

  test("an invalid reported difficulty is rejected and writes nothing", async () => {
    const repository = createSqliteRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    const world = await worldWithGuestMember(repository, instance);
    await instance.updateWorldSettings(owner, world.id, { settings: { difficulty: "easy" } });
    const assignment = await becomeLiveHost(instance, owner, world.id, new Date("2026-01-03T00:00:00.000Z"));

    await expect(
      instance.reportHostGameRules(
        owner,
        world.id,
        { runtimeEpoch: assignment.runtimeEpoch, hostToken: assignment.hostToken, gamerules: {}, difficulty: "nightmare" as never },
        new Date("2026-01-03T00:00:30.000Z")
      )
    ).rejects.toMatchObject({ code: "invalid_world_settings" });
    const details = await instance.getWorld(owner, world.id, new Date("2026-01-03T00:00:31.000Z"));
    expect(details.settings?.difficulty).toBe("easy");
  });

  test("a world that never had settings gains a gamerules-only settings object", async () => {
    const repository = createSqliteRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    const world = await worldWithGuestMember(repository, instance);
    const assignment = await becomeLiveHost(instance, owner, world.id, new Date("2026-01-03T00:00:00.000Z"));

    const reported = await instance.reportHostGameRules(
      owner,
      world.id,
      { runtimeEpoch: assignment.runtimeEpoch, hostToken: assignment.hostToken, gamerules: { pvp: false } },
      new Date("2026-01-03T00:01:00.000Z")
    );

    expect(reported.settings).toEqual({ gamerules: { pvp: false } });
    expect(reported.settingsRevision).toBe(1);
  });

  test("reports merge per key with previously stored gamerules", async () => {
    const repository = createSqliteRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    const world = await worldWithGuestMember(repository, instance);
    await instance.updateWorldSettings(owner, world.id, { settings: { gamerules: { keepInventory: true } } });
    const assignment = await becomeLiveHost(instance, owner, world.id, new Date("2026-01-03T00:00:00.000Z"));

    const reported = await instance.reportHostGameRules(
      owner,
      world.id,
      { runtimeEpoch: assignment.runtimeEpoch, hostToken: assignment.hostToken, gamerules: { pvp: true } },
      new Date("2026-01-03T00:01:00.000Z")
    );

    expect(reported.settings).toEqual({ gamerules: { keepInventory: true, pvp: true } });
  });

  test("reported gamerules are whitelist-validated and nothing is written on rejection", async () => {
    const repository = createSqliteRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    const world = await worldWithGuestMember(repository, instance);
    const assignment = await becomeLiveHost(instance, owner, world.id, new Date("2026-01-03T00:00:00.000Z"));
    const creds = { runtimeEpoch: assignment.runtimeEpoch, hostToken: assignment.hostToken };
    const at = new Date("2026-01-03T00:01:00.000Z");

    await expect(
      instance.reportHostGameRules(owner, world.id, { ...creds, gamerules: { fireTick: true } as never }, at)
    ).rejects.toMatchObject({ code: "invalid_world_settings" });
    await expect(
      instance.reportHostGameRules(owner, world.id, { ...creds, gamerules: { keepInventory: "yes" } as never }, at)
    ).rejects.toMatchObject({ code: "invalid_world_settings" });
    await expect(
      instance.reportHostGameRules(owner, world.id, { ...creds, gamerules: null as never }, at)
    ).rejects.toMatchObject({ code: "invalid_world_settings" });

    const untouched = await instance.getWorld(owner, world.id, at);
    expect(untouched.settings).toBeNull();
    expect(untouched.settingsRevision).toBe(0);
  });

  test("only the authorized live host may report: wrong token, stale epoch, and pre-live phases are 409s", async () => {
    const repository = createSqliteRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    const world = await worldWithGuestMember(repository, instance);
    const at = new Date("2026-01-03T00:01:00.000Z");

    // No runtime at all.
    await expect(
      instance.reportHostGameRules(owner, world.id, { runtimeEpoch: 1, hostToken: "rt-x", gamerules: { pvp: true } }, at)
    ).rejects.toMatchObject({ code: "host_not_active" });

    // host-starting (entered but never heartbeated) is not enough.
    const entered = await instance.enterSession(owner, world.id, {}, new Date("2026-01-03T00:00:00.000Z"));
    const assignment = entered.assignment!;
    await expect(
      instance.reportHostGameRules(
        owner,
        world.id,
        { runtimeEpoch: assignment.runtimeEpoch, hostToken: assignment.hostToken, gamerules: { pvp: true } },
        at
      )
    ).rejects.toMatchObject({ code: "host_not_active" });

    await instance.heartbeatHost(
      owner,
      world.id,
      { runtimeEpoch: assignment.runtimeEpoch, hostToken: assignment.hostToken, joinTarget: "join.example" },
      at
    );

    // Wrong token / missing credentials.
    await expect(
      instance.reportHostGameRules(
        owner,
        world.id,
        { runtimeEpoch: assignment.runtimeEpoch, hostToken: "rt-wrong", gamerules: { pvp: true } },
        at
      )
    ).rejects.toMatchObject({ code: "host_not_active" });
    await expect(
      instance.reportHostGameRules(owner, world.id, { gamerules: { pvp: true } }, at)
    ).rejects.toMatchObject({ code: "host_not_active" });

    // A member who is not the host cannot use the host's credentials.
    await expect(
      instance.reportHostGameRules(
        guest,
        world.id,
        { runtimeEpoch: assignment.runtimeEpoch, hostToken: assignment.hostToken, gamerules: { pvp: true } },
        at
      )
    ).rejects.toMatchObject({ code: "host_not_active" });
  });

  test("a host in host-finalizing may still flush gamerules", async () => {
    const repository = createSqliteRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    const world = await worldWithGuestMember(repository, instance);
    const assignment = await becomeLiveHost(instance, owner, world.id, new Date("2026-01-03T00:00:00.000Z"));

    await instance.beginFinalization(
      owner,
      world.id,
      { runtimeEpoch: assignment.runtimeEpoch, hostToken: assignment.hostToken },
      new Date("2026-01-03T00:01:00.000Z")
    );

    const reported = await instance.reportHostGameRules(
      owner,
      world.id,
      { runtimeEpoch: assignment.runtimeEpoch, hostToken: assignment.hostToken, gamerules: { daylightCycle: false } },
      new Date("2026-01-03T00:01:30.000Z")
    );
    expect(reported.settings).toEqual({ gamerules: { daylightCycle: false } });
  });

  test("an owner save racing the report loses no data: the CAS retry merges against the fresh base", async () => {
    const repository = createSqliteRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    const world = await worldWithGuestMember(repository, instance);
    const assignment = await becomeLiveHost(instance, owner, world.id, new Date("2026-01-03T00:00:00.000Z"));

    // Simulate the owner's PUT landing between the report's read and its
    // CAS write: the first CAS attempt must fail and the retry must merge
    // against the owner's new difficulty instead of resurrecting the old base.
    const original = repository.updateWorldSettingsIfRevision.bind(repository);
    let interleaved = false;
    repository.updateWorldSettingsIfRevision = async (worldId, settingsJson, expectedRevision) => {
      if (!interleaved) {
        interleaved = true;
        await repository.updateWorldSettings(worldId, JSON.stringify({ difficulty: "hard" }));
      }
      return original(worldId, settingsJson, expectedRevision);
    };

    const reported = await instance.reportHostGameRules(
      owner,
      world.id,
      { runtimeEpoch: assignment.runtimeEpoch, hostToken: assignment.hostToken, gamerules: { keepInventory: true } },
      new Date("2026-01-03T00:01:00.000Z")
    );

    expect(reported.settings).toEqual({ difficulty: "hard", gamerules: { keepInventory: true } });
    expect(reported.settingsRevision).toBe(2);
  });

  test("a report that keeps losing the CAS race fails as settings_conflict", async () => {
    const repository = createSqliteRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    const world = await worldWithGuestMember(repository, instance);
    const assignment = await becomeLiveHost(instance, owner, world.id, new Date("2026-01-03T00:00:00.000Z"));

    const original = repository.updateWorldSettingsIfRevision.bind(repository);
    let attempts = 0;
    repository.updateWorldSettingsIfRevision = async (worldId, settingsJson, expectedRevision) => {
      attempts += 1;
      await repository.updateWorldSettings(worldId, JSON.stringify({ difficulty: "hard" }));
      return original(worldId, settingsJson, expectedRevision);
    };

    await expect(
      instance.reportHostGameRules(
        owner,
        world.id,
        { runtimeEpoch: assignment.runtimeEpoch, hostToken: assignment.hostToken, gamerules: { keepInventory: true } },
        new Date("2026-01-03T00:01:00.000Z")
      )
    ).rejects.toMatchObject({ code: "settings_conflict" });
    expect(attempts).toBe(3);
  });

  test("updateWorldSettingsIfRevision writes only when the stored revision matches", async () => {
    const repository = createSqliteRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    const world = await worldWithGuestMember(repository, instance);

    expect(await repository.updateWorldSettingsIfRevision(world.id, JSON.stringify({ gamerules: { pvp: true } }), 0)).toBe(true);
    expect((await repository.getWorldSettings(world.id))?.settingsRevision).toBe(1);
    // Stale expectation: no write, no bump.
    expect(await repository.updateWorldSettingsIfRevision(world.id, JSON.stringify({ gamerules: { pvp: false } }), 0)).toBe(false);
    expect(await repository.getWorldSettings(world.id)).toEqual({ settings: { gamerules: { pvp: true } }, settingsRevision: 1 });
    expect(await repository.updateWorldSettingsIfRevision("missing-world", "{}", 0)).toBe(false);
  });
});
