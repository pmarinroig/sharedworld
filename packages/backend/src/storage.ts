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
