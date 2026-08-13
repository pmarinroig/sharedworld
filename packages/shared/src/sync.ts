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

/**
 * Delta v2 (0.4.0+): the fixed-count chain caps above are replaced by a
 * byte-budget policy — a delta slot is offered while the chain's cumulative
 * delta bytes stay under DELTA_CHAIN_BUDGET_FRACTION × the full artifact
 * size, with the depth ceiling as a generous backstop. v1 clients keep the
 * old caps.
 */
export const DELTA_V2_FORMAT_VERSION = 2;
export const DELTA_V2_MAX_CHAIN_DEPTH = 64;
// 0.4: a generation then stores at most ~1.4× the artifact (1 full + 0.4×
// of deltas) before re-anchoring — the dominant lever on how many bytes a
// kept backup pins in the owner's Drive (was 1.0, which doubled every
// generation's footprint and let chains grow far past what retention could
// ever reclaim).
export const DELTA_CHAIN_BUDGET_FRACTION = 0.4;

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

/**
 * v2 deltas live in their own key namespace: the (baseHash, hash) pair alone
 * also names the v1 blob for the same transition, and the content-addressed
 * dedupe path ("key exists → record without uploading") would otherwise let
 * a v1 blob masquerade as v2 — a silent corruption trap, not a collision
 * you'd notice.
 */
export function storageKeyForRegionBundleDeltaV2(baseHash: string, hash: string): string {
  return `region-bundles/delta2/${baseHash.slice(0, 2)}/${baseHash}-${hash}.bin`;
}

export function storageKeyForPackDeltaV2(baseHash: string, hash: string): string {
  return `packs/delta2/${baseHash.slice(0, 2)}/${baseHash}-${hash}.bin`;
}
