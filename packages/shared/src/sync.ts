import type { FileTransferMode } from "./contracts.ts";

/**
 * The storage-layout and transfer-mode vocabulary shared between the backend's
 * sync planner and the mod's expectations. The backend is the sole planner;
 * client-side plan computation was removed in 0.2.1 (it had no production
 * callers — the mod consumes the backend's plans as-is).
 */

export const WHOLE_GZIP_TRANSFER_MODE: FileTransferMode = "whole-gzip";
export const REGION_FULL_TRANSFER_MODE: FileTransferMode = "region-full";
export const REGION_DELTA_TRANSFER_MODE: FileTransferMode = "region-delta";
export const PACK_FULL_TRANSFER_MODE: FileTransferMode = "pack-full";
export const PACK_DELTA_TRANSFER_MODE: FileTransferMode = "pack-delta";
export const MAX_REGION_DELTA_CHAIN_DEPTH = 12;
export const MAX_PACK_DELTA_CHAIN_DEPTH = 16;
export const NON_REGION_PACK_ID = "non-region";

export function isRegionBundleId(id: string): boolean {
  return id.startsWith("region-bundle:");
}

export function storageKeyForRegionBundleFull(hash: string): string {
  return `region-bundles/full/${hash.slice(0, 2)}/${hash}.bundle`;
}

export function storageKeyForRegionBundleDelta(baseHash: string, hash: string): string {
  return `region-bundles/delta/${baseHash.slice(0, 2)}/${baseHash}-${hash}.bin`;
}

export function storageKeyForPackFull(hash: string): string {
  return `packs/full/${hash.slice(0, 2)}/${hash}.pack`;
}

export function storageKeyForPackDelta(baseHash: string, hash: string): string {
  return `packs/delta/${baseHash.slice(0, 2)}/${baseHash}-${hash}.bin`;
}
