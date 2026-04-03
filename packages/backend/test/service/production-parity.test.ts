import { afterEach, beforeEach, describe, expect, test } from "bun:test";

import {
  GUEST,
  HOST_MEMBER,
  OWNER,
  addMember,
  createD1Fixture,
  createMemoryFixture,
  createWorldAndReleaseSeedAssignment,
  enterAndStartHosting,
  releaseWithAssignment,
  seedUsers
} from "../support/lifecycle.ts";

for (const createFixture of [createMemoryFixture, createD1Fixture]) {
  const labelProbe = createFixture();
  const label = labelProbe.label;
  labelProbe.close();

  describe(`SharedWorldService production lifecycle surface (${label})`, () => {
    let fixture = createFixture();

    beforeEach(() => {
      fixture = createFixture();
    });

    afterEach(() => fixture.close());

    test("waiting flow uses enter observe cancel with explicit waiter session ids", async () => {
      await seedUsers(fixture.repository, OWNER, GUEST);
      const created = await createWorldAndReleaseSeedAssignment(fixture);
      await addMember(fixture.repository, created.world.id, GUEST, "2099-01-01T00:00:10.000Z");

      const hostEntered = await fixture.service.enterSession(
        OWNER,
        created.world.id,
        {},
        new Date("2099-01-02T00:00:00.000Z")
      );
      expect(hostEntered.action).toBe("host");
      expect(hostEntered.assignment).not.toBeNull();

      const waiting = await fixture.service.enterSession(
        GUEST,
        created.world.id,
        {},
        new Date("2099-01-02T00:00:10.000Z")
      );

      expect(waiting.action).toBe("wait");
      expect(waiting.waiterSessionId).not.toBeNull();

      const stillWaiting = await fixture.service.observeWaiting(
        GUEST,
        created.world.id,
        { waiterSessionId: waiting.waiterSessionId! },
        new Date("2099-01-02T00:00:11.000Z")
      );
      expect(stillWaiting.action).toBe("wait");

      await fixture.service.beginFinalization(
        OWNER,
        created.world.id,
        { runtimeEpoch: hostEntered.assignment!.runtimeEpoch, hostToken: hostEntered.assignment!.hostToken },
        new Date("2099-01-02T00:00:20.000Z")
      );
      const completed = await fixture.service.completeFinalization(
        OWNER,
        created.world.id,
        { runtimeEpoch: hostEntered.assignment!.runtimeEpoch, hostToken: hostEntered.assignment!.hostToken },
        new Date("2099-01-02T00:00:30.000Z")
      );
      const promoted = await fixture.service.observeWaiting(
        GUEST,
        created.world.id,
        { waiterSessionId: waiting.waiterSessionId! },
        new Date("2099-01-02T00:00:31.000Z")
      );

      expect(completed.status).toBe("handoff");
      expect(promoted.action).toBe("restart");
      expect(promoted.assignment).toBeNull();

      const restartedEnter = await fixture.service.enterSession(
        GUEST,
        created.world.id,
        {},
        new Date("2099-01-02T00:00:31.500Z")
      );
      expect(restartedEnter.action).toBe("host");
      expect(restartedEnter.assignment?.playerUuid).toBe(GUEST.playerUuid);

      await fixture.service.cancelWaiting(
        GUEST,
        created.world.id,
        { waiterSessionId: waiting.waiterSessionId! },
        new Date("2099-01-02T00:00:32.000Z")
      );
      const restarted = await fixture.service.observeWaiting(
        GUEST,
        created.world.id,
        { waiterSessionId: waiting.waiterSessionId! },
        new Date("2099-01-02T00:00:33.000Z")
      );
      expect(restarted.action).toBe("restart");
      expect(restarted.assignment).toBeNull();

      const restartedAfterCancel = await fixture.service.enterSession(
        GUEST,
        created.world.id,
        {},
        new Date("2099-01-02T00:00:33.500Z")
      );
      expect(restartedAfterCancel.action).toBe("host");
      expect(restartedAfterCancel.assignment?.playerUuid).toBe(GUEST.playerUuid);
    });

    test("graceful and forced release have distinct observable semantics", async () => {
      await seedUsers(fixture.repository, OWNER, GUEST);
      const gracefulWorld = await createWorldAndReleaseSeedAssignment(fixture, { name: "Graceful" });
      await addMember(fixture.repository, gracefulWorld.world.id, GUEST, "2099-01-01T00:00:10.000Z");

      const gracefulEnter = await fixture.service.enterSession(
        OWNER,
        gracefulWorld.world.id,
        {},
        new Date("2099-01-02T00:00:00.000Z")
      );
      expect(gracefulEnter.action).toBe("host");
      expect(gracefulEnter.assignment).not.toBeNull();
      const gracefulWait = await fixture.service.enterSession(
        GUEST,
        gracefulWorld.world.id,
        {},
        new Date("2099-01-02T00:00:10.000Z")
      );
      expect(gracefulWait.action).toBe("wait");
      expect(gracefulWait.waiterSessionId).not.toBeNull();

      const gracefulRelease = await releaseWithAssignment(
        fixture,
        OWNER,
        gracefulWorld.world.id,
        gracefulEnter.assignment!,
        { graceful: true },
        new Date("2099-01-02T00:00:20.000Z")
      );

      expect(gracefulRelease.nextHostUuid).toBe(GUEST.playerUuid);

      const forcedWorld = await createWorldAndReleaseSeedAssignment(fixture, { name: "Forced" });
      await addMember(fixture.repository, forcedWorld.world.id, GUEST, "2099-01-01T00:00:20.000Z");
      const forcedEnter = await fixture.service.enterSession(
        OWNER,
        forcedWorld.world.id,
        {},
        new Date("2099-01-03T00:00:00.000Z")
      );
      expect(forcedEnter.action).toBe("host");
      expect(forcedEnter.assignment).not.toBeNull();
      const forcedWait = await fixture.service.enterSession(
        GUEST,
        forcedWorld.world.id,
        {},
        new Date("2099-01-03T00:00:10.000Z")
      );
      expect(forcedWait.action).toBe("wait");
      expect(forcedWait.waiterSessionId).not.toBeNull();

      const forcedRelease = await releaseWithAssignment(
        fixture,
        OWNER,
        forcedWorld.world.id,
        forcedEnter.assignment!,
        { graceful: false },
        new Date("2099-01-03T00:00:20.000Z")
      );
      const runtimeAfterForced = await fixture.service.runtimeStatus(
        GUEST,
        forcedWorld.world.id,
        new Date("2099-01-03T00:00:21.000Z")
      );

      expect(forcedRelease.nextHostUuid).toBeNull();
      expect(runtimeAfterForced.phase).toBe("handoff-waiting");
      expect(runtimeAfterForced.candidateUuid).toBe(GUEST.playerUuid);
    });

    test("revoked host can still upload and finalize with explicit runtime authorization", async () => {
      await seedUsers(fixture.repository, OWNER, HOST_MEMBER);
      const created = await createWorldAndReleaseSeedAssignment(fixture, { name: "Revoked Host" });
      await addMember(fixture.repository, created.world.id, HOST_MEMBER, "2099-01-01T00:00:10.000Z");

      const hostAssignment = await enterAndStartHosting(
        fixture,
        HOST_MEMBER,
        created.world.id,
        "join.member",
        new Date("2099-01-02T00:00:00.000Z")
      );

      await fixture.service.kickMember(
        OWNER,
        created.world.id,
        HOST_MEMBER.playerUuid,
        new Date("2099-01-02T00:00:10.000Z")
      );

      const plan = await fixture.service.prepareUploads(HOST_MEMBER, created.world.id, {
        runtimeEpoch: hostAssignment.runtimeEpoch,
        hostToken: hostAssignment.hostToken,
        files: [],
        nonRegionPack: null
      });
      const begun = await fixture.service.beginFinalization(
        HOST_MEMBER,
        created.world.id,
        { runtimeEpoch: hostAssignment.runtimeEpoch, hostToken: hostAssignment.hostToken },
        new Date("2099-01-02T00:00:20.000Z")
      );
      const completed = await fixture.service.completeFinalization(
        HOST_MEMBER,
        created.world.id,
        { runtimeEpoch: hostAssignment.runtimeEpoch, hostToken: hostAssignment.hostToken },
        new Date("2099-01-02T00:00:30.000Z")
      );

      expect(plan.worldId).toBe(created.world.id);
      expect(begun.status).toBe("finalizing");
      expect(completed.status).toBe("idle");
    });

    test("revoked live host is no longer connectable and waiters stay waiting until finalization completes", async () => {
      await seedUsers(fixture.repository, OWNER, HOST_MEMBER, GUEST);
      const created = await createWorldAndReleaseSeedAssignment(fixture, { name: "Revoked Host Waiting" });
      await addMember(fixture.repository, created.world.id, HOST_MEMBER, "2099-01-01T00:00:10.000Z");
      await addMember(fixture.repository, created.world.id, GUEST, "2099-01-01T00:00:20.000Z");

      const hostAssignment = await enterAndStartHosting(
        fixture,
        HOST_MEMBER,
        created.world.id,
        "join.member",
        new Date("2099-01-02T00:00:00.000Z")
      );
      await fixture.service.kickMember(
        OWNER,
        created.world.id,
        HOST_MEMBER.playerUuid,
        new Date("2099-01-02T00:00:10.000Z")
      );

      const waitingAfterKick = await fixture.service.enterSession(
        GUEST,
        created.world.id,
        {},
        new Date("2099-01-02T00:00:11.000Z")
      );
      const freshEnterAfterKick = await fixture.service.enterSession(
        GUEST,
        created.world.id,
        { waiterSessionId: waitingAfterKick.waiterSessionId! },
        new Date("2099-01-02T00:00:12.000Z")
      );

      expect(waitingAfterKick.action).toBe("wait");
      expect(waitingAfterKick.waiterSessionId).not.toBeNull();
      expect(waitingAfterKick.runtime?.phase).toBe("host-live");
      expect(waitingAfterKick.runtime?.revokedAt).toBe("2099-01-02T00:00:10.000Z");
      expect(freshEnterAfterKick.action).toBe("wait");

      await fixture.service.beginFinalization(
        HOST_MEMBER,
        created.world.id,
        { runtimeEpoch: hostAssignment.runtimeEpoch, hostToken: hostAssignment.hostToken },
        new Date("2099-01-02T00:00:20.000Z")
      );
      await fixture.service.completeFinalization(
        HOST_MEMBER,
        created.world.id,
        { runtimeEpoch: hostAssignment.runtimeEpoch, hostToken: hostAssignment.hostToken },
        new Date("2099-01-02T00:00:30.000Z")
      );

      const promoted = await fixture.service.observeWaiting(
        GUEST,
        created.world.id,
        { waiterSessionId: waitingAfterKick.waiterSessionId! },
        new Date("2099-01-02T00:00:31.000Z")
      );

      expect(promoted.action).toBe("restart");
      expect(promoted.runtime?.phase).toBe("host-starting");
    });
  });
}
