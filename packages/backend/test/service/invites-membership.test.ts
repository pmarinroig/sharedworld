import { describe, expect, test } from "bun:test";

import { MemorySharedWorldRepository } from "../../src/memory-repository.ts";
import { authVerifier, createBlobSigner, createTestService } from "../support/service-fixtures.ts";

describe("SharedWorldService invites and membership", () => {
  test("expired invite codes are rejected", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld({ playerUuid: "player-owner", playerName: "Owner" }, "Friends SMP", "friends-smp");
    const invite = await instance.createInvite({ playerUuid: "player-owner", playerName: "Owner" }, world.id, new Date("2026-01-01T00:00:00.000Z"));
    invite.expiresAt = "2026-01-01T00:00:01.000Z";

    await expect(
      instance.redeemInvite(
        { playerUuid: "player-guest", playerName: "Guest" },
        { code: invite.code },
        new Date("2026-01-03T00:00:00.000Z")
      )
    ).rejects.toThrow("expired");

    const replacement = await instance.createInvite(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      new Date("2026-01-03T00:00:00.000Z")
    );
    expect(replacement.id).not.toBe(invite.id);
  });

  test("owner reuses the active share code until it expires or rotates", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld({ playerUuid: "player-owner", playerName: "Owner" }, "Friends SMP", "friends-smp");

    const firstInvite = await instance.createInvite(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      new Date("2026-01-01T00:00:00.000Z")
    );
    const secondInvite = await instance.createInvite(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      new Date("2026-01-02T00:00:00.000Z")
    );

    expect(secondInvite.id).toBe(firstInvite.id);
    expect(secondInvite.code).toBe(firstInvite.code);

    const ownerView = await instance.getWorld(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      new Date("2026-01-02T00:00:00.000Z")
    );
    expect(ownerView.activeInviteCode?.code).toBe(firstInvite.code);
  });

  test("share codes are reusable for multiple friends and hidden from members", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld({ playerUuid: "player-owner", playerName: "Owner" }, "Friends SMP", "friends-smp");
    const invite = await instance.createInvite(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      new Date("2026-01-01T00:00:00.000Z")
    );

    const guestOneWorld = await instance.redeemInvite(
      { playerUuid: "player-guest-1", playerName: "Guest One" },
      { code: invite.code },
      new Date("2026-01-01T00:05:00.000Z")
    );
    const guestTwoWorld = await instance.redeemInvite(
      { playerUuid: "player-guest-2", playerName: "Guest Two" },
      { code: invite.code },
      new Date("2026-01-01T00:06:00.000Z")
    );

    expect(guestOneWorld.id).toBe(world.id);
    expect(guestTwoWorld.id).toBe(world.id);
    expect(guestOneWorld.activeInviteCode).toBeNull();
    expect(guestTwoWorld.activeInviteCode).toBeNull();
    await expect(repository.isWorldMember(world.id, "player-guest-1")).resolves.toBe(true);
    await expect(repository.isWorldMember(world.id, "player-guest-2")).resolves.toBe(true);
  });

  test("removed members can rejoin with the same active share code", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld({ playerUuid: "player-owner", playerName: "Owner" }, "Friends SMP", "friends-smp");
    const invite = await instance.createInvite(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      new Date("2026-01-01T00:00:00.000Z")
    );

    await instance.redeemInvite(
      { playerUuid: "player-guest", playerName: "Guest" },
      { code: invite.code },
      new Date("2026-01-01T00:05:00.000Z")
    );
    await instance.kickMember(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      "player-guest",
      new Date("2026-01-01T00:06:00.000Z")
    );

    await expect(instance.getWorld({ playerUuid: "player-guest", playerName: "Guest" }, world.id)).rejects.toThrow("not found");

    const rejoined = await instance.redeemInvite(
      { playerUuid: "player-guest", playerName: "Guest Again" },
      { code: invite.code },
      new Date("2026-01-01T00:07:00.000Z")
    );

    expect(rejoined.id).toBe(world.id);
    expect(rejoined.activeInviteCode).toBeNull();
    await expect(repository.isWorldMember(world.id, "player-guest")).resolves.toBe(true);
  });

  test("kicked members get membership_revoked from session endpoints while the world stays active", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld({ playerUuid: "player-owner", playerName: "Owner" }, "Friends SMP", "friends-smp");
    await repository.addMembership({
      worldId: world.id,
      playerUuid: "player-guest",
      playerName: "Guest",
      role: "member",
      joinedAt: "2026-01-02T00:00:00.000Z",
      deletedAt: null
    });

    await instance.claimHost(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      { joinTarget: "example.test:25565" },
      new Date("2099-01-03T00:00:00.000Z")
    );
    await instance.kickMember(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      "player-guest",
      new Date("2099-01-03T00:01:00.000Z")
    );

    await expect(instance.activeHost({ playerUuid: "player-guest", playerName: "Guest" }, world.id)).rejects.toMatchObject({ code: "membership_revoked", status: 403 });
    await expect(instance.resolveJoin({ playerUuid: "player-guest", playerName: "Guest" }, world.id)).rejects.toMatchObject({ code: "membership_revoked", status: 403 });
    await expect(
      instance.setPlayerPresence(
        { playerUuid: "player-guest", playerName: "Guest" },
        world.id,
        { present: true, guestSessionEpoch: 1, presenceSequence: 1 },
        new Date("2099-01-03T00:01:30.000Z")
      )
    ).rejects.toMatchObject({ code: "membership_revoked", status: 403 });
    await expect(
      instance.handoffReady(
        { playerUuid: "player-guest", playerName: "Guest" },
        world.id,
        { waiting: true },
        new Date("2099-01-03T00:02:00.000Z")
      )
    ).rejects.toMatchObject({ code: "membership_revoked", status: 403 });

    const ownerStatus = await instance.activeHost({ playerUuid: "player-owner", playerName: "Owner" }, world.id);
    expect(ownerStatus.activeLease?.hostUuid).toBe("player-owner");
  });

  test("session endpoints still reject never-members with forbidden", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld({ playerUuid: "player-owner", playerName: "Owner" }, "Friends SMP", "friends-smp");

    await instance.claimHost(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      { joinTarget: "example.test:25565" },
      new Date("2099-01-03T00:00:00.000Z")
    );

    await expect(instance.resolveJoin({ playerUuid: "player-outsider", playerName: "Outsider" }, world.id)).rejects.toMatchObject({ code: "forbidden", status: 403 });
    await expect(instance.activeHost({ playerUuid: "player-outsider", playerName: "Outsider" }, world.id)).rejects.toMatchObject({ code: "forbidden", status: 403 });
  });

  test("only the owner can manage share codes", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld({ playerUuid: "player-owner", playerName: "Owner" }, "Friends SMP", "friends-smp");
    await repository.addMembership({
      worldId: world.id,
      playerUuid: "player-guest",
      playerName: "Guest",
      role: "member",
      joinedAt: new Date().toISOString(),
      deletedAt: null
    });

    await expect(instance.createInvite({ playerUuid: "player-guest", playerName: "Guest" }, world.id)).rejects.toThrow("Only the SharedWorld owner can manage invite codes.");

    const invite = await instance.createInvite({ playerUuid: "player-owner", playerName: "Owner" }, world.id);

    await expect(instance.resetInvite({ playerUuid: "player-guest", playerName: "Guest" }, world.id)).rejects.toThrow("Only the SharedWorld owner can reset invite codes.");

    const memberView = await instance.redeemInvite(
      { playerUuid: "player-guest", playerName: "Guest" },
      { code: invite.code },
      new Date("2026-01-01T00:05:00.000Z")
    );
    expect(memberView.activeInviteCode).toBeNull();
  });

  test("rotating a share code invalidates the previous code immediately", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld({ playerUuid: "player-owner", playerName: "Owner" }, "Friends SMP", "friends-smp");
    const initialInvite = await instance.createInvite(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      new Date("2026-01-01T00:00:00.000Z")
    );

    const rotated = await instance.resetInvite(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      new Date("2026-01-01T00:10:00.000Z")
    );

    expect(rotated.invite.code).not.toBe(initialInvite.code);
    expect(rotated.revokedInviteIds).toContain(initialInvite.id);

    await expect(
      instance.redeemInvite(
        { playerUuid: "player-guest-1", playerName: "Guest One" },
        { code: initialInvite.code },
        new Date("2026-01-01T00:11:00.000Z")
      )
    ).rejects.toThrow("no longer active");

    const worldFromRotatedCode = await instance.redeemInvite(
      { playerUuid: "player-guest-2", playerName: "Guest Two" },
      { code: rotated.invite.code },
      new Date("2026-01-01T00:12:00.000Z")
    );
    expect(worldFromRotatedCode.id).toBe(world.id);
  });

  test("historical redeemed invite rows stay inactive", async () => {
    const repository = new MemorySharedWorldRepository();
    const { signer } = createBlobSigner();
    const instance = createTestService(repository, authVerifier, signer, {});
    await repository.upsertUser({ playerUuid: "player-owner", playerName: "Owner", createdAt: new Date().toISOString() });
    const world = await repository.createWorld({ playerUuid: "player-owner", playerName: "Owner" }, "Friends SMP", "friends-smp");
    const invite = await instance.createInvite(
      { playerUuid: "player-owner", playerName: "Owner" },
      world.id,
      new Date("2026-01-01T00:00:00.000Z")
    );

    invite.status = "redeemed";

    await expect(
      instance.redeemInvite(
        { playerUuid: "player-guest", playerName: "Guest" },
        { code: invite.code },
        new Date("2026-01-01T00:05:00.000Z")
      )
    ).rejects.toThrow("no longer active");
  });
});
