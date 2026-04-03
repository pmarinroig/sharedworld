import {
  STORAGE_LINK_TTL_MS,
  type CreateStorageLinkRequest,
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
    const expiresAt = new Date(now.getTime() + STORAGE_LINK_TTL_MS).toISOString();
    const authUrl = this.buildStorageAuthUrl(id, state);
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
      await this.repository.updateStorageLinkSession(session.id, { status: "expired", errorMessage: "Storage link session expired." });
      session.status = "expired";
      session.errorMessage = "Storage link session expired.";
    }
    return summarizeStorageLinkSession(session);
  }

  async completeStorageLink(sessionId: string, request: StorageLinkCompleteRequest, now = new Date()): Promise<StorageLinkSession> {
    const session = await this.repository.getStorageLinkSession(sessionId);
    if (!session) {
      throw new HttpError(404, "storage_link_not_found", "Storage link session not found.");
    }
    if (new Date(session.expiresAt).getTime() < now.getTime()) {
      throw new HttpError(410, "storage_link_expired", "Storage link session expired.");
    }

    const account = await this.exchangeGoogleAuth(session, request, now);
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
      throw new HttpError(500, "storage_link_missing", "Storage link completion failed.");
    }
    return summarizeStorageLinkSession(refreshed);
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
      throw new HttpError(404, "storage_link_not_found", "Storage link session not found.");
    }
    if (session.playerUuid !== ctx.playerUuid) {
      throw new HttpError(403, "forbidden", "Storage link session does not belong to this player.");
    }
    return session;
  }

  private buildStorageAuthUrl(sessionId: string, state: string): string {
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
      prompt: "consent",
      scope: this.env.GOOGLE_OAUTH_SCOPES ?? "openid email profile https://www.googleapis.com/auth/drive.appdata",
      state: `${sessionId}:${state}`
    });
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
    const tokenPayload = await tokenResponse.json() as { access_token: string; refresh_token?: string; expires_in: number };
    const userResponse = await fetch("https://openidconnect.googleapis.com/v1/userinfo", {
      headers: {
        authorization: `Bearer ${tokenPayload.access_token}`
      }
    });
    if (!userResponse.ok) {
      throw new HttpError(401, "oauth_profile_failed", "Failed to read Google account profile.");
    }
    const user = await userResponse.json() as { sub: string; email?: string; name?: string };
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
