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
import type { AuthVerifier } from "./../service/context.ts";
import {
  buildCertificateSignedPayload,
  decodeBase64Field,
  verifyCertificateSignature,
  verifyNonceSignature
} from "./certificate.ts";
import { MojangServicesKeyProvider, type ServicesKeyProvider } from "./services-keys.ts";

const JOIN_VERIFICATION_DELAYS_MS = [0, 150, 300, 600, 1200] as const;

const PLAYER_NAME_PATTERN = /^\w{1,16}$/;

export class AuthDomainService {
  private readonly servicesKeys: ServicesKeyProvider;

  constructor(
    private readonly repository: SharedWorldRepository,
    private readonly authVerifier: AuthVerifier,
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

  /**
   * Certificate-based auth: the client proves account ownership by signing
   * the challenge nonce with its Mojang-certified profile key. Fully offline
   * against the cached services key set — no sessionserver involved (Mojang
   * blocks that endpoint for Cloudflare Workers egress).
   */
  async completeCertAuth(request: AuthCompleteCertRequest, now = new Date()): Promise<SessionToken> {
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
    const servicesKeys = await this.servicesKeys.playerCertificateKeys(now);
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

  private async verifyJoinedIdentity(playerName: string, serverId: string) {
    // The delay ladder serves two failure modes at once: Mojang propagation
    // lag (verifyJoin resolves null) and transient session-server trouble
    // (verifyJoin throws identity_verification_unavailable). Both consume
    // attempts; a single transient blip must not abort verification.
    let transientFailure: HttpError | null = null;
    for (const delayMs of JOIN_VERIFICATION_DELAYS_MS) {
      if (delayMs > 0) {
        await delay(delayMs);
      }
      try {
        const verified = await this.authVerifier.verifyJoin(playerName, serverId);
        if (verified) {
          return verified;
        }
      } catch (error) {
        if (error instanceof HttpError && error.code === "identity_verification_unavailable") {
          transientFailure = error;
          if (error.upstreamStatus === 429) {
            // Mojang is rate-limiting this worker's egress; burning the
            // remaining ladder attempts within ~2s only deepens the
            // throttling. Surface the retryable 503 immediately.
            break;
          }
          continue;
        }
        throw error;
      }
    }
    if (transientFailure) {
      // Mojang was unreachable for at least part of the window, so "not
      // joined" cannot be trusted as terminal: tell the client to try again
      // soon rather than implying the identity proof failed. A 429 carries
      // Mojang's own clamped Retry-After from the verifier; keep it.
      transientFailure.retryAfterSeconds ??= 10;
      throw transientFailure;
    }
    throw new HttpError(403, "identity_verification_failed", "Failed to verify Minecraft identity.");
  }
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => {
    setTimeout(resolve, ms);
  });
}
