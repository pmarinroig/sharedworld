import {
  INVITE_TTL_MS,
  type InviteCode,
  type KickMemberResponse,
  type RedeemInviteRequest,
  type ResetInviteResponse,
  type WorldDetails,
  type WorldMembership
} from "../../../shared/src/index.ts";

import { HttpError } from "../http.ts";
import { inviteCode as generateInviteCode, randomId } from "../ids.ts";
import type { RequestContext } from "../repository.ts";
import type { ServiceContext } from "./context.ts";
import { publishWorldEvent, requireOwner, requireWorldDetails, worldNotFoundError } from "./runtime-access.ts";
import { getWorld } from "./worlds.ts";

export async function createInvite(svc: ServiceContext, ctx: RequestContext, worldId: string, now: Date): Promise<InviteCode> {
  const world = await requireWorldDetails(svc, worldId, ctx.playerUuid);
  requireOwner(world, ctx, "manage invite codes");
  await svc.repository.revokeSupersededInvites(worldId);
  const activeInvite = await svc.repository.getActiveInvite(worldId, now);
  if (activeInvite) {
    return activeInvite;
  }
  const invite: InviteCode = {
    id: randomId("invite"),
    worldId,
    code: generateInviteCode(),
    createdByUuid: ctx.playerUuid,
    createdAt: now.toISOString(),
    expiresAt: new Date(now.getTime() + INVITE_TTL_MS).toISOString(),
    status: "active"
  };
  const created = await svc.repository.createInvite(worldId, ctx, invite);
  // Concurrent resets/creates can each insert a code; self-heal so exactly one
  // stays active, and hand back whichever code won.
  await svc.repository.revokeSupersededInvites(worldId);
  return await svc.repository.getActiveInvite(worldId, now) ?? created;
}

export async function redeemInvite(svc: ServiceContext, ctx: RequestContext, request: RedeemInviteRequest, now: Date): Promise<WorldDetails> {
  const code = request.code.trim().toUpperCase();
  const invite = await svc.repository.getInviteByCode(code);
  if (!invite) {
    throw new HttpError(404, "invite_not_found", "Invite code not found.");
  }
  if (invite.status !== "active") {
    throw new HttpError(409, "invite_inactive", "Invite code is no longer active.");
  }
  if (new Date(invite.expiresAt).getTime() < now.getTime()) {
    throw new HttpError(410, "invite_expired", "Invite code has expired.");
  }
  if (!await svc.repository.hasActiveWorld(invite.worldId)) {
    // The world was deleted after the code was issued; refuse before inserting
    // a membership row that would reference a soft-deleted world forever.
    throw worldNotFoundError();
  }

  await svc.repository.addMembership({
    worldId: invite.worldId,
    playerUuid: ctx.playerUuid,
    playerName: ctx.playerName,
    role: "member",
    joinedAt: now.toISOString(),
    deletedAt: null,
    canUseCommands: false
  });
  await publishWorldEvent(svc, invite.worldId, "membership-changed");

  return getWorld(svc, ctx, invite.worldId, now);
}

export async function setMemberCommandPermission(
  svc: ServiceContext,
  ctx: RequestContext,
  worldId: string,
  targetPlayerUuid: string,
  canUseCommands: boolean
): Promise<WorldMembership> {
  const world = await requireWorldDetails(svc, worldId, ctx.playerUuid);
  requireOwner(world, ctx, "change member command permissions");
  if (targetPlayerUuid === world.ownerUuid) {
    throw new HttpError(400, "cannot_modify_owner", "The SharedWorld owner always has full command permissions.");
  }
  const updated = await svc.repository.setMembershipCommandPermission(worldId, targetPlayerUuid, canUseCommands);
  if (!updated) {
    throw new HttpError(404, "member_not_found", "SharedWorld member not found.");
  }
  const membership = (await svc.repository.listMemberships(worldId))
    .find((member) => member.playerUuid === targetPlayerUuid);
  if (!membership) {
    throw new HttpError(404, "member_not_found", "SharedWorld member not found.");
  }
  await publishWorldEvent(svc, worldId, "membership-changed");
  return membership;
}

export async function resetInvite(svc: ServiceContext, ctx: RequestContext, worldId: string, now: Date): Promise<ResetInviteResponse> {
  const world = await requireWorldDetails(svc, worldId, ctx.playerUuid);
  requireOwner(world, ctx, "reset invite codes");
  const revokedInviteIds = await svc.repository.revokeActiveInvites(worldId);
  const invite = await createInvite(svc, ctx, worldId, now);
  return {
    revokedInviteIds,
    invite
  };
}

export async function kickMember(
  svc: ServiceContext,
  ctx: RequestContext,
  worldId: string,
  removedPlayerUuid: string,
  now: Date
): Promise<KickMemberResponse> {
  const world = await requireWorldDetails(svc, worldId, ctx.playerUuid);
  requireOwner(world, ctx, "remove members");
  if (removedPlayerUuid === world.ownerUuid) {
    throw new HttpError(400, "cannot_remove_owner", "The SharedWorld owner cannot be removed.");
  }
  const result = await svc.repository.kickMember(worldId, removedPlayerUuid, now.toISOString());
  if (!result) {
    throw new HttpError(404, "member_not_found", "SharedWorld member not found.");
  }
  // Kicking rotates the share code: the previous code stays valid for up to
  // seven days otherwise, and the kicked player could immediately rejoin with it.
  await svc.repository.revokeActiveInvites(worldId);
  await createInvite(svc, ctx, worldId, now);
  // P6: if the kicked player is the current host, the coordinator marks the
  // runtime revoked (they may still finalize their owned epoch, not stay live).
  await svc.realtime.coordinator(worldId).memberRevoked(removedPlayerUuid, now);
  await publishWorldEvent(svc, worldId, "membership-changed");
  // The kicked player is no longer a member, so the member fan-out misses
  // them — and they are exactly who needs the push most.
  await svc.realtime.notifyUsers({ worldId, kind: "membership-changed" }, [removedPlayerUuid]);
  return result;
}
