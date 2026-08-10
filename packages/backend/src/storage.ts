import type { StorageProviderType } from "../../shared/src/index.ts";

import { HttpError } from "./http.ts";
import type { Env, R2ObjectBody } from "./env.ts";
import type { SharedWorldRepository } from "./repository.ts";
import { GoogleDriveStorageProvider } from "./storage/drive.ts";

export interface StorageBinding {
  provider: StorageProviderType;
  storageAccountId: string | null;
}

export interface StoredBlob {
  body: ReadableStream | null;
  contentType: string;
  /** Byte length of `body` (the partial length for a 206), null when unknown. */
  size: number | null;
  /** 206 when body is the requested partial range, 200 for the whole blob. */
  status: 200 | 206;
  /** Verbatim Content-Range header value for a 206 response, else null. */
  contentRange: string | null;
  arrayBuffer(): Promise<ArrayBuffer>;
}

export interface BlobRange {
  offset: number;
  /** Inclusive end byte, or null for "to the end of the blob". */
  endInclusive: number | null;
}

/**
 * Single ascending byte range only ("bytes=N-" / "bytes=N-M"). Anything else
 * (multi-range, suffix ranges, malformed input) returns null and the caller
 * serves the full blob with 200 — permitted by RFC 9110, and SharedWorld
 * clients only ever send the two supported forms for transfer resume.
 */
export function parseSingleByteRange(header: string | null | undefined): BlobRange | null {
  if (!header) {
    return null;
  }
  const match = /^bytes=(\d+)-(\d*)$/.exec(header.trim());
  if (!match) {
    return null;
  }
  const offset = Number(match[1]);
  const endInclusive = match[2] === "" ? null : Number(match[2]);
  if (!Number.isSafeInteger(offset) || (endInclusive != null && (!Number.isSafeInteger(endInclusive) || endInclusive < offset))) {
    return null;
  }
  return { offset, endInclusive };
}

export interface StorageQuota {
  usedBytes: number | null;
  totalBytes: number | null;
}

export interface StorageProvider {
  readonly provider: StorageProviderType;
  exists(binding: StorageBinding, storageKey: string): Promise<boolean>;
  put(binding: StorageBinding, storageKey: string, body: ReadableStream | ArrayBuffer | Uint8Array | string, contentType: string): Promise<void>;
  /** range beyond the end of the blob throws 416 range_not_satisfiable. */
  get(binding: StorageBinding, storageKey: string, range?: BlobRange | null): Promise<StoredBlob | null>;
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

  async get(_binding: StorageBinding, storageKey: string, range?: BlobRange | null): Promise<StoredBlob | null> {
    if (!this.env.BLOBS) {
      return null;
    }
    if (range) {
      // Ranged R2 gets throw on out-of-bounds offsets, so bound the request
      // against the object's real size first (head is cheap) and answer 416
      // ourselves for offsets past the end.
      const head = await this.env.BLOBS.head(storageKey);
      if (!head) {
        return null;
      }
      if (range.offset >= head.size) {
        throw new HttpError(416, "range_not_satisfiable", "Requested range is beyond the end of the stored blob.");
      }
      const endInclusive = Math.min(range.endInclusive ?? head.size - 1, head.size - 1);
      const length = endInclusive - range.offset + 1;
      const object = await this.env.BLOBS.get(storageKey, { range: { offset: range.offset, length } });
      if (!object) {
        return null;
      }
      return {
        body: object.body,
        contentType: object.httpMetadata?.contentType ?? "application/octet-stream",
        size: length,
        status: 206,
        contentRange: `bytes ${range.offset}-${endInclusive}/${head.size}`,
        arrayBuffer() {
          return object.arrayBuffer();
        }
      };
    }
    const object = await this.env.BLOBS.get(storageKey);
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

export type ResumableProbe =
  | { status: "incomplete"; receivedUpTo: number }
  | { status: "complete"; fileId: string; size: number }
  | { status: "expired" };

/**
 * Optional provider capability behind direct-to-provider resumable uploads.
 * Session-init and probe/register run in the worker (they are the metered,
 * credentialed provider calls); the blob bytes themselves flow client →
 * provider via the session URL and never touch the worker.
 */
export interface ResumableUploadCapable {
  createResumableSession(binding: StorageBinding, storageKey: string, contentType: string, expectedSize: number): Promise<string>;
  probeResumableSession(binding: StorageBinding, sessionUrl: string, expectedSize: number): Promise<ResumableProbe>;
  /** Records the storage_objects row from provider-reported facts; deletes a superseded old object. */
  registerUploadedObject(binding: StorageBinding, storageKey: string, fileId: string, size: number, contentType: string): Promise<void>;
  /** Best-effort delete of a provider object by its provider id (cleanup paths). */
  deleteObjectById(binding: StorageBinding, fileId: string): Promise<void>;
}

export function resumableCapable(provider: StorageProvider): (StorageProvider & ResumableUploadCapable) | null {
  return "createResumableSession" in provider ? (provider as StorageProvider & ResumableUploadCapable) : null;
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
    status: 200,
    contentRange: null,
    arrayBuffer() {
      return object.arrayBuffer();
    }
  };
}
