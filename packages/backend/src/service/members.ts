import {
  INVITE_TTL_MS,
  type InviteCode,
  type KickMemberResponse,
  type RedeemInviteRequest,
  type ResetInviteResponse,
  type WorldDetails
} from "../../../shared/src/index.ts";

import { HttpError } from "../http.ts";
import { inviteCode as generateInviteCode, randomId } from "../ids.ts";
import type { RequestContext } from "../repository.ts";
import type { ServiceContext } from "./context.ts";
import { requireOwner, requireWorldDetails } from "./runtime-access.ts";
import { getWorld } from "./worlds.ts";

export async function createInvite(svc: ServiceContext, ctx: RequestContext, worldId: string, now: Date): Promise<InviteCode> {
  const world = await requireWorldDetails(svc, worldId, ctx.playerUuid);
  requireOwner(world, ctx, "manage invite codes");
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
  return svc.repository.createInvite(worldId, ctx, invite);
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

  await svc.repository.addMembership({
    worldId: invite.worldId,
    playerUuid: ctx.playerUuid,
    playerName: ctx.playerName,
    role: "member",
    joinedAt: now.toISOString(),
    deletedAt: null
  });

  return getWorld(svc, ctx, invite.worldId, now);
}

export async function resetInvite(svc: ServiceContext, ctx: RequestContext, worldId: string, now: Date): Promise<ResetInviteResponse> {
  const world = await requireWorldDetails(svc, worldId, ctx.playerUuid);
  requireOwner(world, ctx, "reset invite codes");
  const revokedInviteIds = await svc.repository.revokeActiveInvites(worldId, now.toISOString());
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
  return result;
}
