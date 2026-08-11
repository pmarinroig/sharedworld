import type {
  AuthChallenge,
  AuthCompleteCertRequest,
  AuthCompleteRequest,
  DevAuthCompleteRequest,
  DevSessionToken,
  SessionToken
} from "../../../shared/src/index.ts";

import { HttpError } from "../http.ts";
import { randomId, randomServerId } from "../ids.ts";
import type { Env } from "../env.ts";
import type { SharedWorldRepository } from "../repository.ts";
import {
  buildCertificateSignedPayload,
  decodeBase64Field,
  verifyCertificateSignature,
  verifyNonceSignature
} from "./certificate.ts";
import { MojangServicesKeyProvider, type ServicesKeyProvider } from "./services-keys.ts";

const PLAYER_NAME_PATTERN = /^\w{1,16}$/;

export class AuthDomainService {
  private readonly servicesKeys: ServicesKeyProvider;

  constructor(
    private readonly repository: SharedWorldRepository,
    private readonly env: Env
  ) {
    this.servicesKeys = new MojangServicesKeyProvider(repository, env);
  }

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

  /**
   * The legacy joinServer/hasJoined flow, only reachable by clients ≤0.2.1.
   * Mojang answers 403 to all Cloudflare Workers egress on sessionserver, so
   * this flow can NEVER verify an identity from production — every subrequest
   * it would make is a guaranteed failure. Answer immediately with the update
   * notice instead: no Mojang egress, no D1 reads. Shipped legacy clients
   * render this message verbatim and treat the code as terminal.
   */
  async completeAuth(_request: AuthCompleteRequest): Promise<SessionToken> {
    throw new HttpError(
      403,
      "identity_verification_failed",
      "Minecraft no longer accepts the sign-in method used by SharedWorld 0.2.1 and older. Please update SharedWorld to the latest version."
    );
  }

  /**
   * Certificate-based auth: the client proves account ownership by signing
   * the challenge nonce with its Mojang-certified profile key. Fully offline
   * against the cached services key set — no sessionserver involved (Mojang
   * blocks that endpoint for Cloudflare Workers egress).
   */
  async completeCertAuth(request: AuthCompleteCertRequest, now = new Date()): Promise<SessionToken> {
    try {
      return await this.completeCertAuthChecked(request, now);
    } catch (error) {
      if (error instanceof HttpError) {
        // 4xx auth failures never reach errorResponse's >=500 logging — so
        // this line is the only way production logs can answer "is a real
        // certificate being rejected?".
        console.warn("SharedWorld certificate auth rejected", {
          code: error.code,
          status: error.status,
          playerName: request.playerName,
          playerUuid: request.playerUuid
        });
      }
      throw error;
    }
  }

  private async completeCertAuthChecked(request: AuthCompleteCertRequest, now: Date): Promise<SessionToken> {
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

    const playerUuid = (request.playerUuid ?? "").toLowerCase();
    const playerName = request.playerName ?? "";
    if (!PLAYER_NAME_PATTERN.test(playerName)) {
      throw new HttpError(400, "invalid_player_name", "Player name is not a valid Minecraft name.");
    }
    const publicKeyDer = decodeBase64Field(
      request.publicKey ?? "",
      "certificate_invalid",
      "Minecraft profile certificate is invalid."
    );
    const keySignature = decodeBase64Field(
      request.keySignature ?? "",
      "certificate_invalid",
      "Minecraft profile certificate is invalid."
    );
    const nonceSignature = decodeBase64Field(
      request.nonceSignature ?? "",
      "signature_invalid",
      "Challenge signature is invalid."
    );

    if (!Number.isFinite(request.publicKeyExpiresAtMs) || request.publicKeyExpiresAtMs < now.getTime()) {
      throw new HttpError(
        403,
        "certificate_expired",
        "Your Minecraft profile keys have expired. Restart the game to refresh them and try again."
      );
    }

    // buildCertificateSignedPayload also rejects malformed UUIDs.
    const payload = buildCertificateSignedPayload(playerUuid, request.publicKeyExpiresAtMs, publicKeyDer);
    const servicesKeys = await this.servicesKeys.playerCertificateKeys();
    if (!(await verifyCertificateSignature(payload, keySignature, servicesKeys))) {
      throw new HttpError(
        403,
        "certificate_invalid",
        "Minecraft profile certificate is not validly signed for this player."
      );
    }
    if (!(await verifyNonceSignature(publicKeyDer, request.serverId, nonceSignature))) {
      throw new HttpError(
        403,
        "signature_invalid",
        "Challenge signature does not match the certified profile key."
      );
    }

    const createdAt = now.toISOString();
    const session = this.createSessionToken(playerUuid, playerName, now);
    await this.repository.markChallengeUsed(request.serverId, createdAt);
    await this.repository.upsertUser({
      playerUuid,
      playerName,
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

}
