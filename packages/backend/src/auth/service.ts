import type {
  AuthChallenge,
  AuthCompleteRequest,
  DevAuthCompleteRequest,
  DevSessionToken,
  SessionToken
} from "../../../shared/src/index.ts";

import { HttpError } from "../http.ts";
import { randomId, randomServerId } from "../ids.ts";
import type { Env } from "../env.ts";
import type { SharedWorldRepository } from "../repository.ts";
import type { AuthVerifier } from "../service.ts";

const JOIN_VERIFICATION_DELAYS_MS = [0, 150, 300, 600, 1200] as const;

export class AuthDomainService {
  constructor(
    private readonly repository: SharedWorldRepository,
    private readonly authVerifier: AuthVerifier,
    private readonly env: Env
  ) {}

  async createChallenge(now = new Date()): Promise<AuthChallenge> {
    const challenge = {
      serverId: randomServerId(),
      expiresAt: new Date(now.getTime() + 5 * 60_000).toISOString(),
      usedAt: null
    };
    await this.repository.createChallenge(challenge);
    return {
      serverId: challenge.serverId,
      expiresAt: challenge.expiresAt
    };
  }

  async completeAuth(request: AuthCompleteRequest, now = new Date()): Promise<SessionToken> {
    const challenge = await this.repository.getChallenge(request.serverId);
    if (!challenge) {
      throw new HttpError(404, "challenge_not_found", "Authentication challenge not found.");
    }
    if (challenge.usedAt) {
      throw new HttpError(409, "challenge_used", "Authentication challenge has already been used.");
    }
    if (new Date(challenge.expiresAt).getTime() < now.getTime()) {
      throw new HttpError(410, "challenge_expired", "Authentication challenge has expired.");
    }

    const verified = await this.verifyJoinedIdentity(request.playerName, request.serverId);
    const createdAt = now.toISOString();
    const session = this.createSessionToken(verified.playerUuid, verified.playerName, now);

    await this.repository.markChallengeUsed(request.serverId, createdAt);
    await this.repository.upsertUser({
      playerUuid: verified.playerUuid,
      playerName: verified.playerName,
      createdAt
    });
    await this.repository.createSession(session);
    return session;
  }

  async completeDevAuth(request: DevAuthCompleteRequest, now = new Date()): Promise<DevSessionToken> {
    if ((this.env.ALLOW_DEV_AUTH ?? "").toLowerCase() !== "true") {
      throw new HttpError(404, "not_found", "Route not found.");
    }
    if (request.secret !== (this.env.DEV_AUTH_SECRET ?? "")) {
      throw new HttpError(403, "invalid_dev_auth", "SharedWorld developer auth secret is invalid.");
    }

    const createdAt = now.toISOString();
    const session = this.createSessionToken(request.playerUuid, request.playerName, now);
    await this.repository.upsertUser({
      playerUuid: request.playerUuid,
      playerName: request.playerName,
      createdAt
    });
    await this.repository.createSession(session);
    return {
      ...session,
      allowInsecureE4mc: (this.env.ALLOW_DEV_INSECURE_E4MC ?? "").toLowerCase() === "true"
    };
  }

  async getSession(token: string) {
    return this.repository.getSession(token);
  }

  private createSessionToken(playerUuid: string, playerName: string, now: Date): SessionToken {
    return {
      token: randomId("session"),
      playerUuid,
      playerName,
      expiresAt: new Date(
        now.getTime() + Number(this.env.SESSION_TTL_HOURS ?? "168") * 60 * 60_000
      ).toISOString()
    };
  }

  private async verifyJoinedIdentity(playerName: string, serverId: string) {
    for (const delayMs of JOIN_VERIFICATION_DELAYS_MS) {
      if (delayMs > 0) {
        await delay(delayMs);
      }
      const verified = await this.authVerifier.verifyJoin(playerName, serverId);
      if (verified) {
        return verified;
      }
    }
    throw new HttpError(403, "identity_verification_failed", "Failed to verify Minecraft identity.");
  }
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => {
    setTimeout(resolve, ms);
  });
}
