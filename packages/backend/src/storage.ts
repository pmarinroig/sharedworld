import type { StorageProviderType } from "../../shared/src/index.ts";

import { HttpError } from "./http.ts";
import type { Env, R2ObjectBody } from "./env.ts";
import type { SharedWorldRepository, StorageAccountRecord } from "./repository.ts";

export interface StorageBinding {
  provider: StorageProviderType;
  storageAccountId: string | null;
}

export interface StoredBlob {
  body: ReadableStream | null;
  contentType: string;
  size: number;
  arrayBuffer(): Promise<ArrayBuffer>;
}

export interface StorageQuota {
  usedBytes: number | null;
  totalBytes: number | null;
}

export interface StorageProvider {
  readonly provider: StorageProviderType;
  exists(binding: StorageBinding, storageKey: string): Promise<boolean>;
  put(binding: StorageBinding, storageKey: string, body: ReadableStream | ArrayBuffer | Uint8Array | string, contentType: string): Promise<void>;
  get(binding: StorageBinding, storageKey: string): Promise<StoredBlob | null>;
  delete(binding: StorageBinding, storageKey: string): Promise<void>;
  quota(binding: StorageBinding): Promise<StorageQuota>;
}

export class R2StorageProvider implements StorageProvider {
  readonly provider = "r2" as const;

  constructor(private readonly env: Env) {}

  async exists(_binding: StorageBinding, storageKey: string): Promise<boolean> {
    return (await this.env.BLOBS?.head(storageKey)) != null;
  }

  async put(_binding: StorageBinding, storageKey: string, body: ReadableStream | ArrayBuffer | Uint8Array | string, contentType: string): Promise<void> {
    if (!this.env.BLOBS) {
      throw new HttpError(501, "missing_blob_bucket", "R2 binding is not configured.");
    }
    await this.env.BLOBS.put(storageKey, body, { httpMetadata: { contentType } });
  }

  async get(_binding: StorageBinding, storageKey: string): Promise<StoredBlob | null> {
    const object = await this.env.BLOBS?.get(storageKey);
    if (!object) {
      return null;
    }
    return toStoredBlob(object);
  }

  async delete(_binding: StorageBinding, storageKey: string): Promise<void> {
    await this.env.BLOBS?.delete(storageKey);
  }

  async quota(): Promise<StorageQuota> {
    return {
      usedBytes: null,
      totalBytes: null
    };
  }
}

export class GoogleDriveStorageProvider implements StorageProvider {
  readonly provider = "google-drive" as const;
  private static readonly ACCOUNT_LIMITERS = new Map<string, AccountRequestLimiter>();

  constructor(
    private readonly env: Env,
    private readonly repository: SharedWorldRepository
  ) {}

  async exists(binding: StorageBinding, storageKey: string): Promise<boolean> {
    const accountId = requireAccountId(binding);
    return (await this.repository.getStorageObject(this.provider, accountId, storageKey)) !== null;
  }

  async put(binding: StorageBinding, storageKey: string, body: ReadableStream | ArrayBuffer | Uint8Array | string, contentType: string): Promise<void> {
    const account = await this.requireAccount(binding);
    const bytes = await asUint8Array(body);
    const existing = await this.repository.getStorageObject(this.provider, account.id, storageKey);
    const uploaded = await this.withDriveRetries(account, "upload", async () => {
      await this.accountLimiter(account.id).scheduleUploadStart();
      return existing?.objectId
        ? this.updateFile(account, existing.objectId, bytes, contentType)
        : this.createFile(account, storageKey, bytes, contentType);
    });

    await this.repository.upsertStorageObject({
      provider: this.provider,
      storageAccountId: account.id,
      storageKey,
      objectId: uploaded.id,
      contentType,
      size: bytes.byteLength,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString()
    });
  }

  async get(binding: StorageBinding, storageKey: string): Promise<StoredBlob | null> {
    const account = await this.requireAccount(binding);
    const object = await this.repository.getStorageObject(this.provider, account.id, storageKey);
    if (!object) {
      return null;
    }

    const response = await this.withDriveRetries(
      account,
      "download",
      () => this.driveRequestChecked(account, `${apiBase(this.env)}/files/${encodeURIComponent(object.objectId)}?alt=media`, {}, {
        code: "drive_download_failed",
        label: "Google Drive download failed.",
        allowNotFound: true
      })
    );
    if (response == null) {
      await this.repository.deleteStorageObject(this.provider, account.id, storageKey);
      return null;
    }

    const blob = await response.arrayBuffer();
    return {
      body: new ReadableStream({
        start(controller) {
          controller.enqueue(new Uint8Array(blob));
          controller.close();
        }
      }),
      contentType: response.headers.get("content-type") ?? object.contentType,
      size: blob.byteLength,
      async arrayBuffer() {
        return blob;
      }
    };
  }

  async delete(binding: StorageBinding, storageKey: string): Promise<void> {
    const account = await this.requireAccount(binding);
    const object = await this.repository.getStorageObject(this.provider, account.id, storageKey);
    if (!object) {
      return;
    }

    // A failed Drive delete must keep the local object row: dropping the row
    // on error would orphan the Drive file forever, while keeping it lets blob
    // GC retry the delete later. 404 means the file is already gone.
    await this.withDriveRetries(account, "delete", () => this.driveRequestChecked(account, `${apiBase(this.env)}/files/${encodeURIComponent(object.objectId)}`, {
      method: "DELETE"
    }, {
      code: "drive_delete_failed",
      label: "Google Drive delete failed.",
      allowNotFound: true
    }));
    await this.repository.deleteStorageObject(this.provider, account.id, storageKey);
  }

  async quota(binding: StorageBinding): Promise<StorageQuota> {
    const account = await this.requireAccount(binding);
    const response = await this.driveRequest(account, `${apiBase(this.env)}/about?fields=storageQuota`);
    if (!response.ok) {
      return {
        usedBytes: null,
        totalBytes: null
      };
    }
    const payload = await response.json() as { storageQuota?: { usage?: string; limit?: string } };
    return {
      usedBytes: payload.storageQuota?.usage ? Number(payload.storageQuota.usage) : null,
      totalBytes: payload.storageQuota?.limit ? Number(payload.storageQuota.limit) : null
    };
  }

  private async requireAccount(binding: StorageBinding): Promise<StorageAccountRecord> {
    const accountId = requireAccountId(binding);
    const account = await this.repository.getStorageAccount(accountId);
    if (!account) {
      throw new HttpError(400, "storage_account_not_found", "Linked Google Drive account not found.");
    }
    return account;
  }

  private async createFile(account: StorageAccountRecord, storageKey: string, bytes: Uint8Array, contentType: string) {
    const boundary = `sharedworld-${crypto.randomUUID()}`;
    const metadata = JSON.stringify({
      name: driveObjectName(storageKey),
      parents: ["appDataFolder"]
    });
    const body = new Blob([
      `--${boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n${metadata}\r\n`,
      `--${boundary}\r\nContent-Type: ${contentType}\r\n\r\n`,
      copyArrayBuffer(bytes),
      `\r\n--${boundary}--\r\n`
    ]);
    const response = await this.driveRequest(account, `${uploadBase(this.env)}/files?uploadType=multipart`, {
      method: "POST",
      headers: {
        "content-type": `multipart/related; boundary=${boundary}`
      },
      body
    });
    if (!response.ok) {
      throw await driveError(response, "drive_upload_failed", "Google Drive upload failed.");
    }
    return await response.json() as { id: string };
  }

  private async updateFile(account: StorageAccountRecord, objectId: string, bytes: Uint8Array, contentType: string) {
    const response = await this.driveRequest(account, `${uploadBase(this.env)}/files/${encodeURIComponent(objectId)}?uploadType=media`, {
      method: "PATCH",
      headers: {
        "content-type": contentType
      },
      body: copyArrayBuffer(bytes)
    });
    if (!response.ok) {
      throw await driveError(response, "drive_upload_failed", "Google Drive upload failed.");
    }
    return await response.json() as { id: string };
  }

  private async ensureAccessToken(account: StorageAccountRecord, forceRefresh = false): Promise<string> {
    if (!forceRefresh && account.accessToken && (!account.tokenExpiresAt || new Date(account.tokenExpiresAt).getTime() > Date.now() + 60_000)) {
      return account.accessToken;
    }
    if (!account.refreshToken) {
      throw new HttpError(401, "drive_reauth_required", "Google Drive authorization needs to be refreshed.");
    }

    const response = await fetch("https://oauth2.googleapis.com/token", {
      method: "POST",
      headers: {
        "content-type": "application/x-www-form-urlencoded"
      },
      body: new URLSearchParams({
        client_id: this.env.GOOGLE_OAUTH_CLIENT_ID ?? "",
        client_secret: this.env.GOOGLE_OAUTH_CLIENT_SECRET ?? "",
        refresh_token: account.refreshToken,
        grant_type: "refresh_token"
      })
    });
    if (!response.ok) {
      const detail = await response.json().catch(() => null) as { error?: string } | null;
      if (detail?.error === "invalid_grant") {
        // The stored refresh token was revoked or expired at Google. Drop it so
        // the account reports unhealthy and the client asks for a fresh
        // (forced-consent) Google Drive connection instead of retrying forever.
        await this.repository.createOrUpdateStorageAccount({
          ...account,
          refreshToken: null,
          updatedAt: new Date().toISOString()
        });
      }
      throw new HttpError(401, "drive_reauth_required", "Google Drive access needs to be renewed. Connect Google Drive again from Minecraft, then retry.");
    }
    const payload = await response.json() as { access_token: string; expires_in: number };
    const updated = await this.repository.createOrUpdateStorageAccount({
      ...account,
      accessToken: payload.access_token,
      tokenExpiresAt: new Date(Date.now() + payload.expires_in * 1000).toISOString(),
      updatedAt: new Date().toISOString()
    });
    return updated.accessToken ?? payload.access_token;
  }

  private async driveRequest(account: StorageAccountRecord, url: string, init: RequestInit = {}, retried = false): Promise<Response> {
    const token = await this.ensureAccessToken(account, retried);
    const headers = new Headers(init.headers);
    headers.set("authorization", `Bearer ${token}`);
    const response = await fetch(url, {
      ...init,
      headers
    });
    if (response.status === 401 && !retried && account.refreshToken) {
      return this.driveRequest(account, url, init, true);
    }
    return response;
  }

  /**
   * driveRequest that turns any non-OK response into a thrown HttpError so
   * withDriveRetries can see (and retry) transient failures. Callers that
   * treat 404 as a tombstone opt in via allowNotFound and receive null.
   */
  private async driveRequestChecked(
    account: StorageAccountRecord,
    url: string,
    init: RequestInit,
    options: { code: string; label: string; allowNotFound?: boolean }
  ): Promise<Response | null> {
    const response = await this.driveRequest(account, url, init);
    if (options.allowNotFound && response.status === 404) {
      return null;
    }
    if (!response.ok) {
      throw await driveError(response, options.code, options.label);
    }
    return response;
  }

  private accountLimiter(accountId: string): AccountRequestLimiter {
    let limiter = GoogleDriveStorageProvider.ACCOUNT_LIMITERS.get(accountId);
    if (!limiter) {
      limiter = new AccountRequestLimiter(Math.max(1, Number.parseInt(this.env.DRIVE_MAX_UPLOAD_STARTS_PER_SECOND ?? "3", 10) || 3));
      GoogleDriveStorageProvider.ACCOUNT_LIMITERS.set(accountId, limiter);
    }
    return limiter;
  }

  private async withDriveRetries<T>(
    account: StorageAccountRecord,
    operation: "upload" | "download" | "delete",
    task: () => Promise<T>
  ): Promise<T> {
    const baseDelayMs = Math.max(1, Number.parseInt(this.env.DRIVE_RETRY_BASE_DELAY_MS ?? "750", 10) || 750);
    const maxDelayMs = Math.max(baseDelayMs, Number.parseInt(this.env.DRIVE_RETRY_MAX_DELAY_MS ?? "8000", 10) || 8_000);
    const maxAttempts = operation === "upload" ? 5 : 4;
    let attempt = 0;
    let lastError: unknown = null;

    while (attempt < maxAttempts) {
      attempt += 1;
      try {
        return await task();
      } catch (error) {
        lastError = error;
        const status = driveStatusCode(error);
        if (!isRetryableDriveFailure(error) || attempt >= maxAttempts) {
          throw await this.finalDriveFailure(error, operation, account, attempt);
        }
        const delayMs = Math.min(maxDelayMs, baseDelayMs * (1 << (attempt - 1))) + Math.floor(Math.random() * Math.max(50, baseDelayMs / 2));
        console.warn("SharedWorld retrying Google Drive request", {
          operation,
          accountId: account.id,
          attempt,
          status,
          delayMs
        });
        await sleep(delayMs);
      }
    }

    throw lastError instanceof Error ? lastError : new Error("Google Drive request failed.");
  }

  /**
   * Terminal handling for a Drive failure: log it (4xx here never reaches
   * errorResponse's >=500 logging, so this is the only record), and turn a
   * missing-consent 403 into the re-link path — null the refresh token so the
   * account reports unhealthy and the wizard shows the connect step, and tell
   * the user about the checkbox. Google's granular consent lets a user finish
   * OAuth without granting Drive access, which is invisible until the first
   * real Drive call lands here.
   */
  private async finalDriveFailure(
    error: unknown,
    operation: "upload" | "download" | "delete",
    account: StorageAccountRecord,
    attempt: number
  ): Promise<unknown> {
    const status = driveStatusCode(error);
    const bodyHead = error instanceof HttpError ? error.upstreamBody : undefined;
    console.warn("SharedWorld Google Drive request failed", {
      operation,
      accountId: account.id,
      attempt,
      status,
      bodyHead
    });
    if (status === 403 && isInsufficientScopeBody(bodyHead)) {
      await this.repository.createOrUpdateStorageAccount({
        ...account,
        refreshToken: null,
        updatedAt: new Date().toISOString()
      });
      const reauth = new HttpError(
        401,
        "drive_reauth_required",
        "Google Drive was connected without the Drive access permission. Reconnect Google Drive from Minecraft and tick the Drive access checkbox on the Google screen."
      );
      reauth.upstreamStatus = 403;
      return reauth;
    }
    return error;
  }
}

export function createStorageProvider(env: Env, repository: SharedWorldRepository): StorageProvider {
  const provider = (env.ACTIVE_STORAGE_PROVIDER ?? "google-drive") === "r2"
    ? new R2StorageProvider(env)
    : new GoogleDriveStorageProvider(env, repository);
  return provider;
}

function toStoredBlob(object: R2ObjectBody): StoredBlob {
  return {
    body: object.body,
    contentType: object.httpMetadata?.contentType ?? "application/octet-stream",
    size: object.size,
    arrayBuffer() {
      return object.arrayBuffer();
    }
  };
}

function requireAccountId(binding: StorageBinding): string {
  if (!binding.storageAccountId) {
    throw new HttpError(400, "missing_storage_account", "World is not linked to a storage account.");
  }
  return binding.storageAccountId;
}

async function asUint8Array(body: ReadableStream | ArrayBuffer | Uint8Array | string): Promise<Uint8Array> {
  if (body instanceof Uint8Array) {
    return body;
  }
  if (body instanceof ArrayBuffer) {
    return new Uint8Array(body);
  }
  if (typeof body === "string") {
    return new TextEncoder().encode(body);
  }
  const response = new Response(body);
  return new Uint8Array(await response.arrayBuffer());
}

function apiBase(env: Env): string {
  return env.GOOGLE_DRIVE_API_BASE ?? "https://www.googleapis.com/drive/v3";
}

function uploadBase(env: Env): string {
  const api = env.GOOGLE_DRIVE_API_BASE ?? "https://www.googleapis.com/drive/v3";
  return api.replace("/drive/v3", "/upload/drive/v3");
}

function copyArrayBuffer(bytes: Uint8Array): ArrayBuffer {
  const copy = new Uint8Array(bytes.byteLength);
  copy.set(bytes);
  return copy.buffer;
}

function driveObjectName(storageKey: string): string {
  const bytes = new TextEncoder().encode(storageKey);
  const base64 = btoa(String.fromCharCode(...bytes))
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/g, "");
  return `sharedworld-${base64}`;
}

/** Builds the thrown HttpError for a non-OK Drive response, keeping the body head for reason checks and logs. */
async function driveError(response: Response, code: string, label: string): Promise<HttpError> {
  let text = "";
  try {
    text = await response.text();
  } catch {
    // Body unavailable; the status alone still identifies the failure.
  }
  const error = new HttpError(
    response.status,
    code,
    text ? `${label} HTTP ${response.status}: ${text}` : `${label} HTTP ${response.status}.`
  );
  error.upstreamStatus = response.status;
  error.upstreamBody = text.slice(0, 400);
  return error;
}

function driveStatusCode(error: unknown): number | null {
  // Every Drive failure is thrown as HttpError(status = response status), so
  // the status field is authoritative; never parse it out of message text.
  return error instanceof HttpError ? error.status : null;
}

/**
 * Google answers 403 for both transient rate limiting and permanent
 * conditions (missing consent scope, storage quota, daily caps). Only the
 * rate-limit reasons deserve a retry; a permanent 403 must fail fast instead
 * of burning the whole ladder against a condition that cannot change.
 */
function isRetryableDriveFailure(error: unknown): boolean {
  const status = driveStatusCode(error);
  if (status === 429 || (status !== null && status >= 500)) {
    return true;
  }
  if (status !== 403) {
    return false;
  }
  const body = (error instanceof HttpError ? error.upstreamBody ?? "" : "").toLowerCase();
  return body.includes("ratelimitexceeded");
}

function isInsufficientScopeBody(bodyHead: string | undefined): boolean {
  const body = (bodyHead ?? "").toLowerCase();
  return body.includes("insufficientpermissions")
    || body.includes("insufficient_scope")
    || body.includes("access_token_scope_insufficient")
    || body.includes("insufficient authentication scopes");
}

function sleep(delayMs: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, delayMs));
}

class AccountRequestLimiter {
  private nextAllowedAt = 0;

  constructor(private readonly maxStartsPerSecond: number) {}

  async scheduleUploadStart(): Promise<void> {
    const intervalMs = Math.max(1, Math.ceil(1000 / this.maxStartsPerSecond));
    const now = Date.now();
    const scheduled = Math.max(now, this.nextAllowedAt);
    this.nextAllowedAt = scheduled + intervalMs;
    const waitMs = scheduled - now;
    if (waitMs > 0) {
      await sleep(waitMs);
    }
  }
}
