import type { Env } from "../env.ts";
import type { RealtimeService } from "../realtime/service.ts";
import type { SharedWorldRepository } from "../repository.ts";
import type { WorldRuntimeRecord } from "../runtime-protocol.ts";
import type { StorageProvider } from "../storage.ts";
import type { StorageUsageCache } from "../storage-usage-cache.ts";
import type { StorageLinkDomainService } from "../storage/link-service.ts";
import { mintBlobStamp } from "./blob-stamp.ts";

export interface SignedBlobRequest<TMethod extends "PUT" | "GET" = "PUT" | "GET"> {
  method: TMethod;
  url: string;
  headers: Record<string, string>;
  expiresAt: string;
}

export interface BlobUrlSigner {
  signUpload(worldId: string, storageKey: string, requestOrigin?: string): Promise<SignedBlobRequest<"PUT">>;
  signDownload(worldId: string, storageKey: string, requestOrigin?: string): Promise<SignedBlobRequest<"GET">>;
  deleteBlob?(storageKey: string): Promise<void>;
}

/**
 * The dependencies every domain module operates on. Domain modules are plain
 * functions over this context; only the SharedWorldService facade constructs it.
 */
export interface ServiceContext {
  repository: SharedWorldRepository;
  blobSigner: BlobUrlSigner;
  storageProvider: StorageProvider;
  storageLinks: StorageLinkDomainService;
  realtime: RealtimeService;
  env: Env;
  /** Workers-Cache adapter for storage usage/quota; null where unavailable (tests). */
  storageUsageCache: StorageUsageCache | null;
}

export const RUNTIME_EPOCH_HEADER = "x-sharedworld-runtime-epoch";
export const HOST_TOKEN_HEADER = "x-sharedworld-host-token";
export const BLOB_STAMP_HEADER = "x-sharedworld-blob-stamp";

/**
 * Upload URLs carry the current runtime epoch/token as headers so the blob
 * upload route can re-validate host authority when the bytes arrive — plus,
 * when a signing secret is configured, an HMAC blob stamp that lets that
 * re-validation skip the coordinator round-trip. Clients of every version
 * echo signed headers verbatim, so even pre-0.4 relay uploads ride the
 * stamped fast path automatically.
 */
export async function signUploadForWorld(
  svc: ServiceContext,
  worldId: string,
  storageKey: string,
  runtime: Pick<WorldRuntimeRecord, "runtimeEpoch" | "runtimeToken">,
  requestOrigin?: string
): Promise<SignedBlobRequest<"PUT">> {
  const signed = await svc.blobSigner.signUpload(worldId, storageKey, requestOrigin);
  const stamp = await mintBlobStamp(svc.env, { worldId, runtimeEpoch: runtime.runtimeEpoch, storageKey }, new Date());
  return {
    ...signed,
    headers: {
      ...signed.headers,
      [RUNTIME_EPOCH_HEADER]: String(runtime.runtimeEpoch),
      [HOST_TOKEN_HEADER]: runtime.runtimeToken ?? "",
      ...(stamp == null ? {} : { [BLOB_STAMP_HEADER]: stamp })
    }
  };
}

export function signDownloadForWorld(
  svc: ServiceContext,
  worldId: string,
  storageKey: string,
  requestOrigin?: string
): Promise<SignedBlobRequest<"GET">> {
  return svc.blobSigner.signDownload(worldId, storageKey, requestOrigin);
}
