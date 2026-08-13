import { describe, expect, test } from "bun:test";

import { HttpError } from "../../src/http.ts";
import { HOST_DISCONNECT_GRACE_MS } from "../../src/realtime/coordinator.ts";
import { actor, makeCoordinator, member, type CoordinatorHarness } from "../support/realtime.ts";

const T0 = new Date("2026-01-03T00:00:00.000Z");

function at(seconds: number): Date {
  return new Date(T0.getTime() + seconds * 1_000);
}

const OWNER = actor("owner-uuid", "Owner");
const GUEST = actor("guest-uuid", "Guest");
const THIRD = actor("third-uuid", "Third");

function seedMembers(h: CoordinatorHarness): void {
  h.effects.memberships = [
    member(OWNER.playerUuid, OWNER.playerName, "owner", "2026-01-01T00:00:00.000Z"),
    member(GUEST.playerUuid, GUEST.playerName, "member", "2026-01-02T00:00:00.000Z"),
    member(THIRD.playerUuid, THIRD.playerName, "member", "2026-01-02T12:00:00.000Z")
  ];
}

async function becomeLiveHost(h: CoordinatorHarness, who = OWNER, now = T0): Promise<{ runtimeEpoch: number; hostToken: string }> {
  const entry = await h.coordinator.enterSession(who, {}, now);
  expect(entry.action).toBe("host");
  const assignment = entry.assignment;
  if (assignment == null) {
    throw new Error("expected a host assignment");
  }
  const status = await h.coordinator.heartbeat(
    who,
    { runtimeEpoch: assignment.runtimeEpoch, hostToken: assignment.hostToken, joinTarget: "join.example:25565" },
    new Date(now.getTime() + 1_000)
  );
  expect(status.phase).toBe("host-live");
  return { runtimeEpoch: assignment.runtimeEpoch, hostToken: assignment.hostToken };
}

async function expectHttpError(promise: Promise<unknown>, code: string, reason?: string): Promise<void> {
  try {
    await promise;
    throw new Error(`expected ${code} but the call succeeded`);
  } catch (error) {
    if (!(error instanceof HttpError)) {
      throw error;
    }
    expect(error.code).toBe(code);
    if (reason !== undefined) {
      expect(error.reason).toBe(reason);
    }
  }
}

describe("session entry and election", () => {
  test("[P1] entering an idle world assigns exactly one host and later entrants wait", async () => {
    const h = makeCoordinator();
    seedMembers(h);
    const first = await h.coordinator.enterSession(OWNER, {}, T0);
    expect(first.action).toBe("host");
    expect(first.assignment?.runtimeEpoch).toBe(1);

    const second = await h.coordinator.enterSession(GUEST, {}, at(1));
    expect(second.action).toBe("wait");
    expect(second.assignment).toBeNull();
    expect(h.store.getRuntime()?.hostUuid).toBe(OWNER.playerUuid);
  });

  test("[P1] re-entry by the assigned starting host replays the same assignment tuple", async () => {
    const h = makeCoordinator();
    seedMembers(h);
    const first = await h.coordinator.enterSession(OWNER, {}, T0);
    const again = await h.coordinator.enterSession(OWNER, {}, at(2));
    expect(again.action).toBe("host");
    expect(again.assignment?.runtimeEpoch).toBe(first.assignment?.runtimeEpoch);
    expect(again.assignment?.hostToken).toBe(first.assignment?.hostToken);
  });

  test("a live host with a join target lets members connect directly", async () => {
    const h = makeCoordinator();
    seedMembers(h);
    await becomeLiveHost(h);
    const entry = await h.coordinator.enterSession(GUEST, {}, at(5));
    expect(entry.action).toBe("connect");
    expect(entry.runtime.joinTarget).toBe("join.example:25565");
  });

  test("[P3] a canceled preferred candidate never strands the world on that candidate", async () => {
    const h = makeCoordinator();
    seedMembers(h);
    // Guest and Third wait on an idle world with a candidate: guest joined first.
    const auth = await becomeLiveHost(h);
    const guestWait = await h.coordinator.enterSession(GUEST, {}, at(5));
    // Host disconnects gracefully; guest+third are waiters.
    expect(guestWait.action).toBe("connect"); // live world connects directly
    await h.coordinator.beginFinalization(OWNER, auth, at(10));
    const guestQueued = await h.coordinator.enterSession(GUEST, {}, at(11));
    expect(guestQueued.action).toBe("wait");
    const thirdQueued = await h.coordinator.enterSession(THIRD, {}, at(12));
    expect(thirdQueued.action).toBe("wait");
    await h.coordinator.completeFinalization(OWNER, auth, at(13));

    // Guest is the preferred candidate but cancels instead of observing.
    await h.coordinator.cancelWaiting(GUEST, guestQueued.waiterSessionId ?? "", at(14));
    const observed = await h.coordinator.observeWaiting(THIRD, thirdQueued.waiterSessionId, at(15));
    expect(observed.action).toBe("restart");
    expect(h.store.getRuntime()?.hostUuid).toBe(THIRD.playerUuid);
  });

  test("[P4] an unrefreshed waiter expires out of candidacy on its own", async () => {
    const h = makeCoordinator();
    seedMembers(h);
    const auth = await becomeLiveHost(h);
    await h.coordinator.beginFinalization(OWNER, auth, at(5));
    const queued = await h.coordinator.enterSession(GUEST, {}, at(6));
    expect(queued.action).toBe("wait");
    await h.coordinator.completeFinalization(OWNER, auth, at(7));
    // Guest never observes again; 121s later Third enters an idle world.
    const entry = await h.coordinator.enterSession(THIRD, {}, at(7 + 121));
    expect(entry.action).toBe("host");
  });
});

describe("fencing", () => {
  test("[P2] a deposed host's old epoch and token cannot mutate the new runtime", async () => {
    const h = makeCoordinator();
    seedMembers(h);
    const oldAuth = await becomeLiveHost(h);
    // Live lease expires (no refresh for 154s); guest acknowledges the
    // unclean shutdown and takes over as the epoch-2 host.
    const reentry = await h.coordinator.enterSession(GUEST, { acknowledgeUncleanShutdown: true }, at(155));
    expect(reentry.action).toBe("host");
    expect(reentry.assignment?.runtimeEpoch).toBe(2);

    // The new host is a different player: the deposed host's error says so.
    await expectHttpError(h.coordinator.heartbeat(OWNER, { ...oldAuth, joinTarget: null }, at(158)), "host_not_active", "replaced");
    await expectHttpError(h.coordinator.beginFinalization(OWNER, oldAuth, at(158)), "host_not_active", "replaced");
    await expectHttpError(
      h.coordinator.validateHostAuthority(OWNER, oldAuth.runtimeEpoch, oldAuth.hostToken, ["host-live"], at(158)),
      "host_not_active",
      "replaced"
    );
    expect(h.store.getRuntime()?.runtimeEpoch).toBe(2);
  });

  test("an expired live lease records the unclean-shutdown warning and re-entry warns before hosting", async () => {
    const h = makeCoordinator();
    seedMembers(h);
    await becomeLiveHost(h);
    const entry = await h.coordinator.enterSession(GUEST, {}, at(155));
    expect(entry.action).toBe("warn-host");
    expect(entry.runtime.uncleanShutdownWarning?.hostUuid).toBe(OWNER.playerUuid);
    const acknowledged = await h.coordinator.enterSession(GUEST, { acknowledgeUncleanShutdown: true }, at(156));
    expect(acknowledged.action).toBe("host");
    expect(acknowledged.assignment?.runtimeEpoch).toBe(2);
  });

  test("release replay: retrying a completed release succeeds without minting authority", async () => {
    const h = makeCoordinator();
    seedMembers(h);
    const auth = await becomeLiveHost(h);
    const released = await h.coordinator.releaseHost(OWNER, { ...auth, graceful: true }, at(10));
    expect(released.graceful).toBe(true);
    const replay = await h.coordinator.releaseHost(OWNER, { ...auth, graceful: true }, at(11));
    expect(replay.releasedAt).toBe(at(11).toISOString());
    expect(h.store.getRuntime()).toBeNull();
  });

  test("a lease-expired epoch is a real authority loss, not a release replay", async () => {
    const h = makeCoordinator();
    seedMembers(h);
    const auth = await becomeLiveHost(h);
    // Expiry recorded the warning for epoch 1. No one took over: the error
    // must say the lease expired, not blame a phantom other host.
    await h.coordinator.runtimeStatus(OWNER, at(155));
    await expectHttpError(h.coordinator.releaseHost(OWNER, { ...auth, graceful: true }, at(156)), "host_not_active", "lease_expired");
    await expectHttpError(h.coordinator.completeFinalization(OWNER, auth, at(156)), "not_finalizing");
  });
});

describe("finalization and revocation", () => {
  test("[P6] a revoked host cannot heartbeat but can still finalize its owned epoch", async () => {
    const h = makeCoordinator();
    seedMembers(h);
    const auth = await becomeLiveHost(h);
    await h.coordinator.memberRevoked(OWNER.playerUuid, at(5));
    const revokedActor = actor(OWNER.playerUuid, OWNER.playerName, { membershipActive: false, everMember: true });

    await expectHttpError(h.coordinator.heartbeat(revokedActor, { ...auth, joinTarget: null }, at(6)), "membership_revoked");
    const begun = await h.coordinator.beginFinalization(revokedActor, auth, at(7));
    expect(begun.status).toBe("finalizing");
    const completed = await h.coordinator.completeFinalization(revokedActor, auth, at(8));
    expect(completed.status).toBe("idle");
  });

  test("completing finalization hands off to the preferred waiting candidate", async () => {
    const h = makeCoordinator();
    seedMembers(h);
    const auth = await becomeLiveHost(h);
    await h.coordinator.beginFinalization(OWNER, auth, at(5));
    const queued = await h.coordinator.enterSession(GUEST, {}, at(6));
    expect(queued.action).toBe("wait");
    const completed = await h.coordinator.completeFinalization(OWNER, auth, at(7));
    expect(completed.status).toBe("handoff");
    expect(completed.nextHostUuid).toBe(GUEST.playerUuid);
  });

  test("[P5] destroying the world clears all state and notifies former members", async () => {
    const h = makeCoordinator();
    seedMembers(h);
    await becomeLiveHost(h);
    await h.coordinator.destroyWorld([OWNER.playerUuid, GUEST.playerUuid]);
    expect(h.store.getRuntime()).toBeNull();
    expect(h.store.listWaiters()).toEqual([]);
    expect(h.effects.alarmAt).toBeNull();
    const deleted = h.effects.published.at(-1);
    expect(deleted?.event.kind).toBe("world-deleted");
    expect(deleted?.recipients).toEqual([OWNER.playerUuid, GUEST.playerUuid]);
  });
});

describe("connection-driven liveness (signal, never truth)", () => {
  test("host socket loss forfeits the lease only after the grace window", async () => {
    const h = makeCoordinator();
    seedMembers(h);
    await becomeLiveHost(h);
    await h.coordinator.hostSocketClosed(OWNER.playerUuid, at(10));
    const grace = h.store.getHostLink().graceDeadlineAt;
    expect(grace).toBe(new Date(at(10).getTime() + HOST_DISCONNECT_GRACE_MS).toISOString());
    // Alarm before the grace deadline: nothing happens.
    await h.coordinator.onAlarm(at(20));
    expect(h.store.getRuntime()?.phase).toBe("host-live");
    // Alarm after the grace deadline: lease forfeited, warning recorded.
    await h.coordinator.onAlarm(at(41));
    expect(h.store.getRuntime()).toBeNull();
    expect(h.store.getWarning()?.hostUuid).toBe(OWNER.playerUuid);
  });

  test("a reconnect inside the grace window cancels forfeiture", async () => {
    const h = makeCoordinator();
    seedMembers(h);
    await becomeLiveHost(h);
    await h.coordinator.hostSocketClosed(OWNER.playerUuid, at(10));
    await h.coordinator.hostSocketConnected(OWNER.playerUuid, at(15));
    await h.coordinator.onAlarm(at(41));
    expect(h.store.getRuntime()?.phase).toBe("host-live");
  });

  test("a due grace deadline is verified against the keepalive before forfeiting (lost reconnect poke)", async () => {
    const h = makeCoordinator();
    seedMembers(h);
    await becomeLiveHost(h);
    // Socket closed at +10; the reconnect happened but its hostSocketConnected
    // poke was lost to a coordinator reset. The keepalive timestamp is the
    // ground truth: fresh keepalives must cancel the forfeiture and repair
    // the link state.
    await h.coordinator.hostSocketClosed(OWNER.playerUuid, at(10));
    h.effects.lastKeepaliveAt = at(39);
    await h.coordinator.onAlarm(at(41));
    expect(h.store.getRuntime()?.phase).toBe("host-live");
    expect(h.store.getHostLink()).toEqual({ connected: true, graceDeadlineAt: null });
    // With the link repaired and keepalives gone stale, expiry still works.
    await h.coordinator.onAlarm(at(41 + 155));
    expect(h.store.getRuntime()).toBeNull();
  });

  test("a lease deadline probes the keepalive even when the connected signal never arrived", async () => {
    const h = makeCoordinator();
    seedMembers(h);
    await becomeLiveHost(h);
    // The gateway's hostSocketConnected poke was lost (coordinator reset), so
    // link.connected stayed false while the client stretched its heartbeats;
    // the probe must still keep a reachable host alive.
    h.effects.lastKeepaliveAt = at(145);
    await h.coordinator.onAlarm(at(155));
    expect(h.store.getRuntime()?.phase).toBe("host-live");
    expect(h.store.getHostLink().connected).toBe(true);
  });

  test("a reachable host's lease extends from its socket keepalive without any heartbeat", async () => {
    const h = makeCoordinator();
    seedMembers(h);
    await becomeLiveHost(h);
    await h.coordinator.hostSocketConnected(OWNER.playerUuid, at(2));
    h.effects.lastKeepaliveAt = at(145);
    // Live-lease deadline (T0+151s from the heartbeat at +1s) has passed.
    await h.coordinator.onAlarm(at(155));
    expect(h.store.getRuntime()?.phase).toBe("host-live");
    // Keepalive goes stale: the next lease-deadline alarm forfeits.
    await h.coordinator.onAlarm(at(155 + 155));
    expect(h.store.getRuntime()).toBeNull();
  });

  test("an over-deadline lease hit by an inbound request probes the keepalive before expiring", async () => {
    const h = makeCoordinator();
    seedMembers(h);
    const auth = await becomeLiveHost(h);
    // The renewal alarm lost the race: an inbound request (e.g. a blob PUT's
    // authority check) lands after the lease deadline while the host's socket
    // keepalive is fresh. The host must be rescued, not expired with a false
    // unclean-shutdown warning.
    h.effects.lastKeepaliveAt = at(145);
    const status = await h.coordinator.runtimeStatus(OWNER, at(155));
    expect(status.phase).toBe("host-live");
    expect(h.store.getWarning()).toBeNull();
    await h.coordinator.validateHostAuthority(OWNER, auth.runtimeEpoch, auth.hostToken, ["host-live"], at(156));
    // With the keepalive stale, the same path still expires the lease.
    await h.coordinator.runtimeStatus(OWNER, at(156 + 155));
    expect(h.store.getRuntime()).toBeNull();
    expect(h.store.getWarning()?.hostUuid).toBe(OWNER.playerUuid);
  });

  test("a successful heartbeat clears an armed socket-grace deadline", async () => {
    const h = makeCoordinator();
    seedMembers(h);
    const auth = await becomeLiveHost(h);
    await h.coordinator.hostSocketClosed(OWNER.playerUuid, at(10));
    expect(h.store.getHostLink().graceDeadlineAt).not.toBeNull();
    // The host is demonstrably reachable over HTTPS; a stale grace deadline
    // must not forfeit its lease while heartbeats keep landing.
    await h.coordinator.heartbeat(OWNER, { ...auth, joinTarget: null }, at(15));
    expect(h.store.getHostLink().graceDeadlineAt).toBeNull();
    // Socket state still belongs to the gateway: connected stays false.
    expect(h.store.getHostLink().connected).toBe(false);
    await h.coordinator.onAlarm(at(41));
    expect(h.store.getRuntime()?.phase).toBe("host-live");
    expect(h.store.getWarning()).toBeNull();
  });

  test("[P7] lease expiry publishes the runtime change so watchers hear about it", async () => {
    const h = makeCoordinator();
    seedMembers(h);
    await becomeLiveHost(h);
    h.effects.published = [];
    await h.coordinator.onAlarm(at(155));
    const runtimeEvents = h.effects.eventsOfKind("runtime-changed");
    expect(runtimeEvents.length).toBeGreaterThan(0);
    expect(runtimeEvents.at(-1)?.runtime?.phase).toBe("idle");
  });
});

describe("room presence", () => {
  test("a host-reported roster mirrors, publishes, and wins over legacy self-reports", async () => {
    const h = makeCoordinator();
    seedMembers(h);
    const auth = await becomeLiveHost(h);
    await h.coordinator.reportLegacyPresence(THIRD, { present: true, guestSessionEpoch: 1, presenceSequence: 1 }, at(3));
    await h.coordinator.reportHostPlayers(
      OWNER.playerUuid,
      auth.runtimeEpoch,
      [{ playerUuid: OWNER.playerUuid, playerName: OWNER.playerName }],
      at(5)
    );
    expect(h.coordinator.roomPlayers(at(6))).toEqual([{ playerUuid: OWNER.playerUuid, playerName: OWNER.playerName }]);
    const presence = h.effects.eventsOfKind("presence-changed");
    expect(presence.at(-1)?.roomPlayers).toEqual([{ playerUuid: OWNER.playerUuid, playerName: OWNER.playerName }]);
    expect(h.effects.mirroredPresence.at(-1)).toEqual([{ playerUuid: OWNER.playerUuid, playerName: OWNER.playerName }]);
  });

  test("a roster report with a stale epoch is dropped", async () => {
    const h = makeCoordinator();
    seedMembers(h);
    await becomeLiveHost(h);
    await h.coordinator.reportHostPlayers(OWNER.playerUuid, 99, [{ playerUuid: "x", playerName: "X" }], at(5));
    expect(h.store.getRoomPlayers()).toBeNull();
  });

  test("legacy presence entries expire on the alarm and the mirror empties", async () => {
    const h = makeCoordinator();
    seedMembers(h);
    await h.coordinator.reportLegacyPresence(GUEST, { present: true, guestSessionEpoch: 1, presenceSequence: 1 }, T0);
    expect(h.coordinator.roomPlayers(at(1))).toHaveLength(1);
    await h.coordinator.onAlarm(at(46));
    expect(h.coordinator.roomPlayers(at(46))).toEqual([]);
    expect(h.effects.mirroredPresence.at(-1)).toEqual([]);
  });

  test("retiring the runtime clears the room and publishes empty presence", async () => {
    const h = makeCoordinator();
    seedMembers(h);
    const auth = await becomeLiveHost(h);
    await h.coordinator.reportHostPlayers(
      OWNER.playerUuid,
      auth.runtimeEpoch,
      [{ playerUuid: OWNER.playerUuid, playerName: OWNER.playerName }],
      at(5)
    );
    await h.coordinator.releaseHost(OWNER, { ...auth, graceful: true }, at(10));
    expect(h.effects.mirroredPresence.at(-1)).toEqual([]);
  });
});

describe("push hygiene", () => {
  test("steady live heartbeats do not republish runtime-changed", async () => {
    const h = makeCoordinator();
    seedMembers(h);
    const auth = await becomeLiveHost(h);
    h.effects.published = [];
    await h.coordinator.heartbeat(OWNER, { ...auth, joinTarget: null }, at(30));
    await h.coordinator.heartbeat(OWNER, { ...auth, joinTarget: null }, at(60));
    expect(h.effects.eventsOfKind("runtime-changed")).toHaveLength(0);
  });

  test("an alarm is always armed while a runtime holds a deadline", async () => {
    const h = makeCoordinator();
    seedMembers(h);
    await becomeLiveHost(h);
    expect(h.effects.alarmAt).not.toBeNull();
  });

  test("access control: a never-member is forbidden, a removed member is told so", async () => {
    const h = makeCoordinator();
    seedMembers(h);
    await expectHttpError(
      h.coordinator.enterSession(actor("stranger", "S", { membershipActive: false, everMember: false }), {}, T0),
      "forbidden"
    );
    await expectHttpError(
      h.coordinator.enterSession(actor("kicked", "K", { membershipActive: false, everMember: true }), {}, T0),
      "membership_revoked"
    );
  });
});
