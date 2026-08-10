import { describe, expect, test } from "bun:test";

import { WorldCoordinator } from "../../src/realtime/coordinator.ts";
import { DoCoordinatorEffects } from "../../src/realtime/do.ts";
import { RecordingEffects, actor, makeCoordinator, member } from "../support/realtime.ts";

/**
 * EB2: the coordinator's D1/storage traffic per call, pinned. Membership
 * lists are cached in the store with explicit invalidation pokes; publish
 * fingerprints persist across "evictions" (fresh coordinator over the same
 * store); alarms are not re-armed at unchanged deadlines.
 */
describe("coordinator efficiency", () => {
  const owner = member("player-owner", "Owner", "owner", "2026-01-01T00:00:00.000Z");

  test("one membership read serves a whole call, and the cache serves the next", async () => {
    const { coordinator, effects } = makeCoordinator();
    effects.memberships = [owner];

    await coordinator.runtimeStatus(actor("player-owner", "Owner"), new Date("2026-01-01T10:00:00.000Z"));
    expect(effects.listMembershipsCalls).toBe(1);

    await coordinator.runtimeStatus(actor("player-owner", "Owner"), new Date("2026-01-01T10:00:30.000Z"));
    expect(effects.listMembershipsCalls).toBe(1);
  });

  test("the TTL expires the cached list", async () => {
    const { coordinator, effects } = makeCoordinator();
    effects.memberships = [owner];

    await coordinator.runtimeStatus(actor("player-owner", "Owner"), new Date("2026-01-01T10:00:00.000Z"));
    await coordinator.runtimeStatus(actor("player-owner", "Owner"), new Date("2026-01-01T10:01:30.000Z"));
    expect(effects.listMembershipsCalls).toBe(2);
  });

  test("membership pokes invalidate immediately", async () => {
    const { coordinator, effects } = makeCoordinator();
    effects.memberships = [owner];
    const now = new Date("2026-01-01T10:00:00.000Z");

    await coordinator.runtimeStatus(actor("player-owner", "Owner"), now);
    expect(effects.listMembershipsCalls).toBe(1);

    effects.memberships = [owner, member("player-guest", "Guest", "member", "2026-01-01T09:00:00.000Z")];
    await coordinator.membershipsChanged(new Date("2026-01-01T10:00:05.000Z"));
    expect(effects.listMembershipsCalls).toBe(2);

    await coordinator.memberRevoked("player-guest", new Date("2026-01-01T10:00:10.000Z"));
    expect(effects.listMembershipsCalls).toBe(3);
  });

  test("publishes carry explicit recipients so the effects layer never re-lists", async () => {
    const { coordinator, effects } = makeCoordinator();
    effects.memberships = [owner, { ...member("player-kicked", "Kicked", "member", "2026-01-01T09:00:00.000Z"), deletedAt: "2026-01-01T09:30:00.000Z" }];

    await coordinator.runtimeStatus(actor("player-owner", "Owner"), new Date("2026-01-01T10:00:00.000Z"));
    expect(effects.published.length).toBeGreaterThan(0);
    for (const entry of effects.published) {
      expect(entry.recipients).toEqual(["player-owner"]);
    }
  });

  test("a cold start over the same store does not rewrite an unchanged mirror", async () => {
    const { coordinator, store, effects } = makeCoordinator();
    effects.memberships = [owner];
    const now = new Date("2026-01-01T10:00:00.000Z");

    await coordinator.runtimeStatus(actor("player-owner", "Owner"), now);
    expect(effects.mirroredRuntimes).toHaveLength(1);

    // Same persisted store, fresh coordinator + effects = a DO eviction.
    const rebornEffects = new RecordingEffects();
    rebornEffects.memberships = [owner];
    const reborn = new WorldCoordinator("world-1", store, rebornEffects);
    await reborn.runtimeStatus(actor("player-owner", "Owner"), new Date("2026-01-01T10:02:00.000Z"));
    expect(rebornEffects.mirroredRuntimes).toHaveLength(0);
    expect(rebornEffects.published).toHaveLength(0);
  });

  test("scheduleAlarm skips storage writes for an unchanged deadline", async () => {
    let sets = 0;
    let deletes = 0;
    const storage = {
      async setAlarm() { sets += 1; },
      async deleteAlarm() { deletes += 1; }
    } as unknown as DurableObjectStorage;
    const effects = new DoCoordinatorEffects({} as never, storage, "world-1");

    const deadline = new Date("2026-01-01T10:00:30.000Z");
    await effects.scheduleAlarm(deadline);
    await effects.scheduleAlarm(new Date(deadline));
    expect(sets).toBe(1);

    await effects.scheduleAlarm(null);
    await effects.scheduleAlarm(null);
    expect(deletes).toBe(1);

    await effects.scheduleAlarm(deadline);
    expect(sets).toBe(2);
  });
});
