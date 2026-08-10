import { describe, expect, test } from "bun:test";

import { actor, makeCoordinator, member, type CoordinatorHarness } from "../support/realtime.ts";

/**
 * SB1: legacy-presence entries must never drive the alarm into a loop.
 * Before this fix, ANY entry's expiresAt was pushed into nextDeadline
 * unconditionally — an already-expired entry then hit the now+1s floor and
 * re-armed a 1-SECOND alarm forever while a runtime existed, silently
 * burning DO duration in production.
 */
describe("legacy presence pruning", () => {
  const T0 = new Date("2026-01-01T10:00:00.000Z");
  const at = (seconds: number) => new Date(T0.getTime() + seconds * 1_000);
  const OWNER = actor("owner-uuid", "Owner");
  const GUEST = actor("guest-uuid", "Guest");

  function seed(h: CoordinatorHarness): void {
    h.effects.memberships = [
      member(OWNER.playerUuid, OWNER.playerName, "owner", "2026-01-01T00:00:00.000Z"),
      member(GUEST.playerUuid, GUEST.playerName, "member", "2026-01-01T01:00:00.000Z")
    ];
  }

  async function liveHost(h: CoordinatorHarness) {
    const entry = await h.coordinator.enterSession(OWNER, {}, T0);
    const assignment = entry.assignment!;
    await h.coordinator.heartbeat(
      OWNER,
      { runtimeEpoch: assignment.runtimeEpoch, hostToken: assignment.hostToken, joinTarget: "join.example:25565" },
      at(1)
    );
  }

  test("an expired legacy entry never arms a sub-second alarm loop", async () => {
    const h = makeCoordinator();
    seed(h);
    await liveHost(h);
    await h.coordinator.reportLegacyPresence(GUEST, { present: true, guestSessionEpoch: 1, presenceSequence: 1 }, at(2));

    // Well past the entry's 45s expiry; the runtime lease (renewed by the
    // fresh keepalive) is the only thing that should own the alarm.
    h.effects.lastKeepaliveAt = at(90);
    await h.coordinator.onAlarm(at(95));
    const firstAlarm = h.effects.alarmAt;
    expect(firstAlarm).not.toBeNull();
    // The next deadline is the renewed lease (~90s out), never now+1s.
    expect(firstAlarm!.getTime() - at(95).getTime()).toBeGreaterThan(30_000);

    // Re-firing must not degrade into 1s re-arms either.
    h.effects.lastKeepaliveAt = at(180);
    await h.coordinator.onAlarm(at(185));
    expect(h.effects.alarmAt!.getTime() - at(185).getTime()).toBeGreaterThan(30_000);
  });

  test("a present entry leaves the roster exactly at its expiry alarm", async () => {
    const h = makeCoordinator();
    seed(h);
    await liveHost(h);
    await h.coordinator.reportLegacyPresence(GUEST, { present: true, guestSessionEpoch: 1, presenceSequence: 1 }, at(2));
    expect(h.coordinator.roomPlayers(at(10)).map((player) => player.playerUuid)).toContain(GUEST.playerUuid);

    // Entry expires at +47s; the alarm fires and republishes the empty roster.
    h.effects.lastKeepaliveAt = at(46);
    await h.coordinator.onAlarm(at(48));
    expect(h.coordinator.roomPlayers(at(48)).map((player) => player.playerUuid)).not.toContain(GUEST.playerUuid);
  });

  test("a present:false tombstone still fences a stale resurrect after its own expiry", async () => {
    const h = makeCoordinator();
    seed(h);
    await liveHost(h);
    await h.coordinator.reportLegacyPresence(GUEST, { present: true, guestSessionEpoch: 2, presenceSequence: 1 }, at(2));
    await h.coordinator.reportLegacyPresence(GUEST, { present: false, guestSessionEpoch: 2, presenceSequence: 2 }, at(5));

    // The tombstone's 45s window has passed and alarms have fired since —
    // but a delayed heartbeat from the OLD session must still be fenced.
    h.effects.lastKeepaliveAt = at(88);
    await h.coordinator.onAlarm(at(90));
    await h.coordinator.reportLegacyPresence(GUEST, { present: true, guestSessionEpoch: 1, presenceSequence: 9 }, at(95));
    expect(h.coordinator.roomPlayers(at(96)).map((player) => player.playerUuid)).not.toContain(GUEST.playerUuid);
  });

  test("tombstones are pruned once they age past the retention window", async () => {
    const h = makeCoordinator();
    seed(h);
    await liveHost(h);
    await h.coordinator.reportLegacyPresence(GUEST, { present: false, guestSessionEpoch: 2, presenceSequence: 1 }, at(2));
    expect(h.store.listLegacyPresence()).toHaveLength(1);

    // Ten minutes past the entry's expiresAt (+47s), the sweep removes it.
    h.effects.lastKeepaliveAt = at(700);
    await h.coordinator.onAlarm(at(710));
    expect(h.store.listLegacyPresence()).toHaveLength(0);
  });
});
