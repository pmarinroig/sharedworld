import {
  STORAGE_LINK_TTL_MS,
  type CreateStorageLinkRequest,
  type StorageAccountSummary,
  type StorageLinkCompleteRequest,
  type StorageLinkSession,
  type StorageProviderType
} from "../../../shared/src/index.ts";

import { HttpError } from "../http.ts";
import { randomId } from "../ids.ts";
import type { Env } from "../env.ts";
import type { RequestContext, SharedWorldRepository, StorageAccountRecord, StorageLinkSessionRecord } from "../repository.ts";

export class StorageLinkDomainService {
  constructor(
    private readonly repository: SharedWorldRepository,
    private readonly env: Env,
    private readonly provider: StorageProviderType
  ) {}

  async createStorageLink(ctx: RequestContext, request: CreateStorageLinkRequest, now = new Date()): Promise<StorageLinkSession> {
    const provider = request.provider ?? this.provider;
    const id = randomId("link");
    const state = randomId("state");
    const completedAt = now.toISOString();
    const expiresAt = new Date(now.getTime() + STORAGE_LINK_TTL_MS).toISOString();
    // Google issues a refresh token only on a consent-screen grant. Skip the
    // consent screen when the player already holds a refreshable account for
    // this provider; force it when they don't (or when the client asks to
    // recover a broken account).
    const hasRefreshableAccount = (await this.repository.findStorageAccountsByOwner(provider, ctx.playerUuid))
      .some((account) => account.refreshToken != null);
    const forceConsent = request.forceConsent === true || !hasRefreshableAccount;
    const authUrl = this.buildStorageAuthUrl(id, state, forceConsent);
    await this.repository.createStorageLinkSession({
      id,
      provider,
      status: "pending",
      authUrl,
      expiresAt,
      linkedAccountEmail: null,
      accountDisplayName: null,
      errorMessage: null,
      playerUuid: ctx.playerUuid,
      storageAccountId: null,
      state,
      createdAt: now.toISOString(),
      completedAt: null
    });
    await this.repository.cancelPendingStorageLinkSessions(ctx.playerUuid, provider, id, completedAt);
    return {
      id,
      provider,
      status: "pending",
      authUrl,
      expiresAt,
      linkedAccountEmail: null,
      accountDisplayName: null,
      errorMessage: null
    };
  }

  async getStorageLinkSession(ctx: RequestContext, sessionId: string, now = new Date()): Promise<StorageLinkSession> {
    const session = await this.requireLinkSessionOwner(ctx, sessionId);
    if (new Date(session.expiresAt).getTime() < now.getTime() && session.status === "pending") {
      await this.repository.updateStorageLinkSession(session.id, { status: "expired", errorMessage: "The Google Drive sign-in took too long. Start it again from Minecraft." });
      session.status = "expired";
      session.errorMessage = "The Google Drive sign-in took too long. Start it again from Minecraft.";
    }
    return summarizeStorageLinkSession(session);
  }

  async cancelStorageLink(ctx: RequestContext, sessionId: string, now = new Date()): Promise<StorageLinkSession> {
    const session = await this.requireLinkSessionOwner(ctx, sessionId);
    if (new Date(session.expiresAt).getTime() < now.getTime() && session.status === "pending") {
      await this.repository.updateStorageLinkSession(session.id, { status: "expired", errorMessage: "The Google Drive sign-in took too long. Start it again from Minecraft." });
      session.status = "expired";
      session.errorMessage = "The Google Drive sign-in took too long. Start it again from Minecraft.";
      return summarizeStorageLinkSession(session);
    }
    if (session.status === "pending") {
      const completedAt = now.toISOString();
      await this.repository.cancelStorageLinkSession(session.id, completedAt);
      session.status = "cancelled";
      session.errorMessage = null;
      session.completedAt = completedAt;
    }
    return summarizeStorageLinkSession(session);
  }

  async completeStorageLink(sessionId: string, request: StorageLinkCompleteRequest, now = new Date()): Promise<StorageLinkSession> {
    const session = await this.repository.getStorageLinkSession(sessionId);
    if (!session) {
      throw new HttpError(404, "storage_link_not_found", "This Google Drive sign-in is no longer active. Start it again from Minecraft.");
    }
    if (session.status === "cancelled") {
      throw new HttpError(409, "storage_link_cancelled", "This Google Drive link is no longer active. Return to Minecraft and start again.");
    }
    if (new Date(session.expiresAt).getTime() < now.getTime()) {
      throw new HttpError(410, "storage_link_expired", "The Google Drive sign-in took too long. Start it again from Minecraft.");
    }
    requireMatchingState(session, request.state);

    let account: StorageAccountRecord;
    try {
      account = await this.exchangeGoogleAuth(session, request, now);
    } catch (error) {
      // A consent-shaped rejection (Drive checkbox unticked, or no lasting
      // grant) marks the session failed so the client shows the reason and
      // offers the forced-consent retry.
      if (error instanceof HttpError && error.code === "storage_link_needs_consent") {
        await this.repository.updateStorageLinkSession(sessionId, {
          status: "failed",
          errorMessage: error.message,
          completedAt: now.toISOString()
        });
      }
      throw error;
    }
    if (account.refreshToken == null) {
      // Google granted a session but no refresh token (an account we have never
      // seen through the consent screen, or one whose grant was revoked). The
      // link would break as soon as the access token expires, so fail now with
      // a retry the client turns into a forced-consent attempt.
      const message = "Google didn't give SharedWorld lasting access to this account. Return to Minecraft and try connecting again.";
      await this.repository.updateStorageLinkSession(sessionId, {
        status: "failed",
        errorMessage: message,
        completedAt: now.toISOString()
      });
      throw new HttpError(409, "storage_link_needs_consent", message);
    }
    await this.repository.updateStorageLinkSession(sessionId, {
      status: "linked",
      linkedAccountEmail: account.email,
      accountDisplayName: account.displayName,
      storageAccountId: account.id,
      completedAt: now.toISOString(),
      errorMessage: null
    });
    const refreshed = await this.repository.getStorageLinkSession(sessionId);
    if (!refreshed) {
      throw new HttpError(500, "storage_link_missing", "Connecting Google Drive didn't finish. Try again from Minecraft.");
    }
    return summarizeStorageLinkSession(refreshed);
  }

  /**
   * The caller's reusable storage account for this provider, preferring one
   * whose authorization can still refresh. `healthy: false` tells the client
   * a fresh (forced-consent) link is needed before the account can be used.
   */
  async getStorageAccountSummary(ctx: RequestContext): Promise<StorageAccountSummary> {
    const accounts = await this.repository.findStorageAccountsByOwner(this.provider, ctx.playerUuid);
    const best = accounts.find((account) => account.refreshToken != null) ?? accounts[0] ?? null;
    return {
      linked: best != null,
      provider: this.provider,
      email: best?.email ?? null,
      displayName: best?.displayName ?? null,
      healthy: best?.refreshToken != null
    };
  }

  async requireCompletedLinkSession(ctx: RequestContext, sessionId: string): Promise<StorageLinkSessionRecord> {
    const session = await this.requireLinkSessionOwner(ctx, sessionId);
    if (session.status !== "linked" || !session.storageAccountId) {
      throw new HttpError(409, "storage_link_incomplete", "Google Drive authorization is not complete yet.");
    }
    return session;
  }

  async requireLinkSessionOwner(ctx: RequestContext, sessionId: string): Promise<StorageLinkSessionRecord> {
    const session = await this.repository.getStorageLinkSession(sessionId);
    if (!session) {
      throw new HttpError(404, "storage_link_not_found", "This Google Drive sign-in is no longer active. Start it again from Minecraft.");
    }
    if (session.playerUuid !== ctx.playerUuid) {
      throw new HttpError(403, "forbidden", "Storage link session does not belong to this player.");
    }
    return session;
  }

  private buildStorageAuthUrl(sessionId: string, state: string, forceConsent: boolean): string {
    const redirectUri = this.env.GOOGLE_OAUTH_REDIRECT_URI ?? `${this.env.PUBLIC_BASE_URL ?? "http://127.0.0.1:8787"}/storage/google/callback`;
    if ((this.env.ALLOW_DEV_GOOGLE_OAUTH ?? "").toLowerCase() === "true") {
      const mockEmail = encodeURIComponent(this.env.DEV_GOOGLE_EMAIL ?? "dev-google@example.com");
      return `${redirectUri}?sessionId=${encodeURIComponent(sessionId)}&state=${encodeURIComponent(state)}&mock=1&mockEmail=${mockEmail}`;
    }

    const params = new URLSearchParams({
      client_id: this.env.GOOGLE_OAUTH_CLIENT_ID ?? "",
      redirect_uri: redirectUri,
      response_type: "code",
      access_type: "offline",
      scope: this.env.GOOGLE_OAUTH_SCOPES ?? "openid email profile https://www.googleapis.com/auth/drive.appdata",
      state: `${sessionId}:${state}`
    });
    if (forceConsent) {
      params.set("prompt", "consent");
    }
    return `https://accounts.google.com/o/oauth2/v2/auth?${params.toString()}`;
  }

  private async exchangeGoogleAuth(session: StorageLinkSessionRecord, request: StorageLinkCompleteRequest, now: Date): Promise<StorageAccountRecord> {
    if ((this.env.ALLOW_DEV_GOOGLE_OAUTH ?? "").toLowerCase() === "true" && request.mockEmail) {
      return this.upsertStorageAccountFromOAuth(
        session,
        {
          sub: request.mockEmail,
          email: request.mockEmail,
          name: request.mockEmail,
          accessToken: "dev-google-token",
          refreshToken: "dev-google-refresh",
          expiresAt: new Date(now.getTime() + 60 * 60_000).toISOString()
        },
        now
      );
    }

    if (!request.code) {
      throw new HttpError(400, "missing_oauth_code", "Google OAuth callback code is required.");
    }
    const redirectUri = this.env.GOOGLE_OAUTH_REDIRECT_URI ?? `${this.env.PUBLIC_BASE_URL ?? "http://127.0.0.1:8787"}/storage/google/callback`;
    const tokenResponse = await fetch("https://oauth2.googleapis.com/token", {
      method: "POST",
      headers: {
        "content-type": "application/x-www-form-urlencoded"
      },
      body: new URLSearchParams({
        code: request.code,
        client_id: this.env.GOOGLE_OAUTH_CLIENT_ID ?? "",
        client_secret: this.env.GOOGLE_OAUTH_CLIENT_SECRET ?? "",
        redirect_uri: redirectUri,
        grant_type: "authorization_code"
      })
    });
    if (!tokenResponse.ok) {
      throw new HttpError(401, "oauth_exchange_failed", "Failed to exchange Google OAuth code.");
    }
    let tokenPayload: { access_token: string; refresh_token?: string; expires_in: number; scope?: string };
    try {
      tokenPayload = await tokenResponse.json() as { access_token: string; refresh_token?: string; expires_in: number; scope?: string };
    } catch {
      throw new HttpError(401, "oauth_exchange_failed", "Failed to exchange Google OAuth code.");
    }
    requireDriveAppDataScope(tokenPayload.scope);
    const userResponse = await fetch("https://openidconnect.googleapis.com/v1/userinfo", {
      headers: {
        authorization: `Bearer ${tokenPayload.access_token}`
      }
    });
    if (!userResponse.ok) {
      throw new HttpError(401, "oauth_profile_failed", "Failed to read Google account profile.");
    }
    let user: { sub: string; email?: string; name?: string };
    try {
      user = await userResponse.json() as { sub: string; email?: string; name?: string };
    } catch {
      throw new HttpError(401, "oauth_profile_failed", "Failed to read Google account profile.");
    }
    return this.upsertStorageAccountFromOAuth(
      session,
      {
        sub: user.sub,
        email: user.email ?? null,
        name: user.name ?? null,
        accessToken: tokenPayload.access_token,
        refreshToken: tokenPayload.refresh_token ?? null,
        expiresAt: new Date(now.getTime() + tokenPayload.expires_in * 1000).toISOString()
      },
      now
    );
  }

  private async upsertStorageAccountFromOAuth(
    session: StorageLinkSessionRecord,
    payload: { sub: string; email: string | null; name: string | null; accessToken: string; refreshToken: string | null; expiresAt: string },
    now: Date
  ): Promise<StorageAccountRecord> {
    const existing = await this.repository.findStorageAccountByExternalId(session.provider, payload.sub);
    return this.repository.createOrUpdateStorageAccount({
      id: existing?.id ?? randomId("storage"),
      provider: session.provider,
      ownerPlayerUuid: session.playerUuid,
      externalAccountId: payload.sub,
      email: payload.email,
      displayName: payload.name,
      accessToken: payload.accessToken,
      refreshToken: payload.refreshToken ?? existing?.refreshToken ?? null,
      tokenExpiresAt: payload.expiresAt,
      createdAt: existing?.createdAt ?? now.toISOString(),
      updatedAt: now.toISOString()
    });
  }
}

const DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata";

/**
 * Google's granular consent makes the Drive permission an optional checkbox:
 * a user can finish the OAuth flow without granting it, leaving a link that
 * looks healthy (valid refresh token) while every Drive call fails with 403.
 * Per RFC 6749 the token response's `scope` is omitted when it equals the
 * request, so an absent field means granted; a present field must contain the
 * Drive scope.
 */
function requireDriveAppDataScope(grantedScope: string | undefined): void {
  if (grantedScope === undefined) {
    return;
  }
  if (grantedScope.split(/\s+/).includes(DRIVE_APPDATA_SCOPE)) {
    return;
  }
  throw new HttpError(
    409,
    "storage_link_needs_consent",
    "Google didn't grant SharedWorld access to its app folder in your Drive. Return to Minecraft, connect again, and tick the Drive access checkbox on the Google screen."
  );
}

/**
 * The OAuth `state` round-trips through the provider as `<sessionId>:<nonce>`
 * (or the bare nonce on the dev-mock flow). The callback is unauthenticated,
 * so the nonce is the only proof the caller actually came from the auth URL
 * this session issued — without it, anyone who learns a pending sessionId
 * could drive the callback.
 */
function requireMatchingState(session: StorageLinkSessionRecord, presentedState: string | null | undefined): void {
  const nonce = presentedState != null && presentedState.startsWith(`${session.id}:`)
    ? presentedState.slice(session.id.length + 1)
    : presentedState;
  if (nonce == null || nonce.length === 0 || nonce !== session.state) {
    throw new HttpError(403, "storage_link_state_mismatch", "This Google Drive sign-in could not be verified. Start it again from Minecraft.");
  }
}

function summarizeStorageLinkSession(session: StorageLinkSessionRecord): StorageLinkSession {
  return {
    id: session.id,
    provider: session.provider,
    status: session.status,
    authUrl: session.authUrl,
    expiresAt: session.expiresAt,
    linkedAccountEmail: session.linkedAccountEmail,
    accountDisplayName: session.accountDisplayName,
    errorMessage: session.errorMessage
  };
}
