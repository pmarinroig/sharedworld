import { describe, expect, test } from "bun:test";

import { actor, makeCoordinator, member, type CoordinatorHarness } from "../support/realtime.ts";

/**
 * SB2: socket-derived guest presence. The socket IS the liveness — no
 * expiry, no alarms for connected entries, blips ride a 15s grace with zero
 * fan-out, and kicked players' re-announces are membership-gated to inert.
 */
describe("coordinator socket presence", () => {
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

  function presenceEvents(h: CoordinatorHarness): number {
    return h.effects.eventsOfKind("presence-changed").length;
  }

  test("a member's announce joins the roster with one publish; a non-member's is dropped", async () => {
    const h = makeCoordinator();
    seed(h);
    await liveHost(h);
    const before = presenceEvents(h);

    await h.coordinator.reportSocketPresence(GUEST.playerUuid, true, at(5));
    expect(h.coordinator.roomPlayers(at(6)).map((player) => player.playerUuid)).toContain(GUEST.playerUuid);
    expect(h.coordinator.roomPlayers(at(6)).find((player) => player.playerUuid === GUEST.playerUuid)?.playerName).toBe("Guest");
    expect(presenceEvents(h)).toBe(before + 1);

    await h.coordinator.reportSocketPresence("outsider-uuid", true, at(7));
    expect(h.coordinator.roomPlayers(at(8)).map((player) => player.playerUuid)).not.toContain("outsider-uuid");
    expect(presenceEvents(h)).toBe(before + 1);
  });

  test("a socket blip inside the grace causes zero fan-out and zero mirror writes", async () => {
    const h = makeCoordinator();
    seed(h);
    await liveHost(h);
    await h.coordinator.reportSocketPresence(GUEST.playerUuid, true, at(5));
    const publishes = presenceEvents(h);
    const mirrors = h.effects.mirroredPresence.length;

    await h.coordinator.presenceSocketClosed(GUEST.playerUuid, at(10));
    expect(h.coordinator.roomPlayers(at(12)).map((player) => player.playerUuid)).toContain(GUEST.playerUuid);
    await h.coordinator.reportSocketPresence(GUEST.playerUuid, true, at(13));

    expect(presenceEvents(h)).toBe(publishes);
    expect(h.effects.mirroredPresence.length).toBe(mirrors);
    expect(h.coordinator.roomPlayers(at(14)).map((player) => player.playerUuid)).toContain(GUEST.playerUuid);
  });

  test("an unreturned socket is pruned at the grace alarm with one publish", async () => {
    const h = makeCoordinator();
    seed(h);
    await liveHost(h);
    await h.coordinator.reportSocketPresence(GUEST.playerUuid, true, at(5));
    await h.coordinator.presenceSocketClosed(GUEST.playerUuid, at(10));
    const publishes = presenceEvents(h);

    // Grace expires at +25; the roster hides the entry from then on, and the
    // alarm prunes it with exactly one presence publish.
    expect(h.coordinator.roomPlayers(at(26)).map((player) => player.playerUuid)).not.toContain(GUEST.playerUuid);
    h.effects.lastKeepaliveAt = at(24);
    await h.coordinator.onAlarm(at(26));
    expect(h.store.listSocketPresence()).toHaveLength(0);
    expect(presenceEvents(h)).toBe(publishes + 1);
  });

  test("host roster wins outright; socket and legacy entries merge deduped otherwise", async () => {
    const h = makeCoordinator();
    seed(h);
    const auth = await (async () => {
      const entry = await h.coordinator.enterSession(OWNER, {}, T0);
      const assignment = entry.assignment!;
      await h.coordinator.heartbeat(OWNER, { runtimeEpoch: assignment.runtimeEpoch, hostToken: assignment.hostToken, joinTarget: "join.example:25565" }, at(1));
      return assignment;
    })();

    // Same guest visible via socket AND a legacy fallback beat: one entry.
    await h.coordinator.reportSocketPresence(GUEST.playerUuid, true, at(5));
    await h.coordinator.reportLegacyPresence(GUEST, { present: true, guestSessionEpoch: 1, presenceSequence: 1 }, at(6));
    expect(h.coordinator.roomPlayers(at(7)).filter((player) => player.playerUuid === GUEST.playerUuid)).toHaveLength(1);

    // Host-reported roster takes over completely while present.
    await h.coordinator.reportHostPlayers(OWNER.playerUuid, auth.runtimeEpoch, [
      { playerUuid: OWNER.playerUuid, playerName: "Owner" }
    ], at(8));
    expect(h.coordinator.roomPlayers(at(9)).map((player) => player.playerUuid)).toEqual([OWNER.playerUuid]);
  });

  test("kick removes the entry and a re-announce stays inert", async () => {
    const h = makeCoordinator();
    seed(h);
    await liveHost(h);
    await h.coordinator.reportSocketPresence(GUEST.playerUuid, true, at(5));

    h.effects.memberships = [member(OWNER.playerUuid, OWNER.playerName, "owner", "2026-01-01T00:00:00.000Z")];
    await h.coordinator.memberRevoked(GUEST.playerUuid, at(10));
    expect(h.coordinator.roomPlayers(at(11)).map((player) => player.playerUuid)).not.toContain(GUEST.playerUuid);

    await h.coordinator.reportSocketPresence(GUEST.playerUuid, true, at(12));
    expect(h.coordinator.roomPlayers(at(13)).map((player) => player.playerUuid)).not.toContain(GUEST.playerUuid);
  });

  test("connected entries arm no alarms; only grace deadlines do", async () => {
    const h = makeCoordinator();
    seed(h);
    await liveHost(h);
    const leaseAlarm = h.effects.alarmAt;
    expect(leaseAlarm).not.toBeNull();

    // A connected guest changes nothing about the alarm schedule.
    await h.coordinator.reportSocketPresence(GUEST.playerUuid, true, at(5));
    expect(h.effects.alarmAt?.getTime()).toBe(leaseAlarm!.getTime());

    // A closed socket arms the (earlier) grace deadline.
    await h.coordinator.presenceSocketClosed(GUEST.playerUuid, at(10));
    expect(h.effects.alarmAt?.getTime()).toBe(at(25).getTime());
  });

  test("retiring the runtime clears socket presence", async () => {
    const h = makeCoordinator();
    seed(h);
    const entry = await h.coordinator.enterSession(OWNER, {}, T0);
    const assignment = entry.assignment!;
    await h.coordinator.heartbeat(OWNER, { runtimeEpoch: assignment.runtimeEpoch, hostToken: assignment.hostToken, joinTarget: "join.example:25565" }, at(1));
    await h.coordinator.reportSocketPresence(GUEST.playerUuid, true, at(5));

    await h.coordinator.releaseHost(OWNER, { graceful: true, runtimeEpoch: assignment.runtimeEpoch, hostToken: assignment.hostToken }, at(20));
    expect(h.store.listSocketPresence()).toHaveLength(0);
  });
});
