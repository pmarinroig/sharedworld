import type { Env } from "../env.ts";
import type { SharedWorldRepository } from "../repository.ts";
import type { WorldRuntimeRecord } from "../runtime-protocol.ts";
import type { StorageProvider } from "../storage.ts";
import type { StorageLinkDomainService } from "../storage/link-service.ts";

export interface AuthVerifier {
  verifyJoin(playerName: string, serverId: string): Promise<{ playerUuid: string; playerName: string } | null>;
}

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
  authVerifier: AuthVerifier;
  blobSigner: BlobUrlSigner;
  storageProvider: StorageProvider;
  storageLinks: StorageLinkDomainService;
  env: Env;
}

export const RUNTIME_EPOCH_HEADER = "x-sharedworld-runtime-epoch";
export const HOST_TOKEN_HEADER = "x-sharedworld-host-token";

/**
 * Upload URLs carry the current runtime epoch/token as headers so the blob
 * upload route can re-validate host authority when the bytes arrive.
 */
export async function signUploadForWorld(
  svc: ServiceContext,
  worldId: string,
  storageKey: string,
  runtime: WorldRuntimeRecord,
  requestOrigin?: string
): Promise<SignedBlobRequest<"PUT">> {
  const signed = await svc.blobSigner.signUpload(worldId, storageKey, requestOrigin);
  return {
    ...signed,
    headers: {
      ...signed.headers,
      [RUNTIME_EPOCH_HEADER]: String(runtime.runtimeEpoch),
      [HOST_TOKEN_HEADER]: runtime.runtimeToken ?? ""
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
