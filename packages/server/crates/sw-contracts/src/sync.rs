//! Storage-layout and transfer-mode vocabulary (`sync.ts`). The backend is
//! the sole planner; keys are content addressed.

use crate::types::FileTransferMode;

pub const WHOLE_GZIP_TRANSFER_MODE: FileTransferMode = FileTransferMode::WholeGzip;
pub const REGION_FULL_TRANSFER_MODE: FileTransferMode = FileTransferMode::RegionFull;
pub const REGION_DELTA_TRANSFER_MODE: FileTransferMode = FileTransferMode::RegionDelta;
pub const PACK_FULL_TRANSFER_MODE: FileTransferMode = FileTransferMode::PackFull;
pub const PACK_DELTA_TRANSFER_MODE: FileTransferMode = FileTransferMode::PackDelta;
pub const MAX_REGION_DELTA_CHAIN_DEPTH: i64 = 12;
pub const MAX_PACK_DELTA_CHAIN_DEPTH: i64 = 16;
pub const NON_REGION_PACK_ID: &str = "non-region";

/// Delta v2 (0.4.0+): byte-budget policy replaces the fixed chain caps.
pub const DELTA_V2_FORMAT_VERSION: i64 = 2;
pub const DELTA_V2_MAX_CHAIN_DEPTH: i64 = 64;
/// A generation stores at most ~1.4× the artifact before re-anchoring.
pub const DELTA_CHAIN_BUDGET_FRACTION: f64 = 0.4;

pub fn is_region_bundle_id(id: &str) -> bool {
    id.starts_with("region-bundle:")
}

fn prefix2(hash: &str) -> &str {
    // JS `slice(0, 2)` on an ASCII hex hash; tolerate shorter strings.
    let end = hash.char_indices().nth(2).map(|(i, _)| i).unwrap_or(hash.len());
    &hash[..end]
}

pub fn storage_key_for_region_bundle_full(hash: &str) -> String {
    format!("region-bundles/full/{}/{}.bundle", prefix2(hash), hash)
}

pub fn storage_key_for_region_bundle_delta(base_hash: &str, hash: &str) -> String {
    format!("region-bundles/delta/{}/{}-{}.bin", prefix2(base_hash), base_hash, hash)
}

pub fn storage_key_for_pack_full(hash: &str) -> String {
    format!("packs/full/{}/{}.pack", prefix2(hash), hash)
}

pub fn storage_key_for_pack_delta(base_hash: &str, hash: &str) -> String {
    format!("packs/delta/{}/{}-{}.bin", prefix2(base_hash), base_hash, hash)
}

/// v2 deltas live in their own namespace so a v1 blob can never masquerade
/// as v2 through the "key exists → record without uploading" dedupe path.
pub fn storage_key_for_region_bundle_delta_v2(base_hash: &str, hash: &str) -> String {
    format!("region-bundles/delta2/{}/{}-{}.bin", prefix2(base_hash), base_hash, hash)
}

pub fn storage_key_for_pack_delta_v2(base_hash: &str, hash: &str) -> String {
    format!("packs/delta2/{}/{}-{}.bin", prefix2(base_hash), base_hash, hash)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn keys_match_ts_layout() {
        assert_eq!(storage_key_for_pack_full("abcdef"), "packs/full/ab/abcdef.pack");
        assert_eq!(storage_key_for_pack_delta("aa11", "bb22"), "packs/delta/aa/aa11-bb22.bin");
        assert_eq!(storage_key_for_pack_delta_v2("aa11", "bb22"), "packs/delta2/aa/aa11-bb22.bin");
        assert_eq!(storage_key_for_region_bundle_full("cc33"), "region-bundles/full/cc/cc33.bundle");
        assert_eq!(
            storage_key_for_region_bundle_delta_v2("cc33", "dd44"),
            "region-bundles/delta2/cc/cc33-dd44.bin"
        );
        assert!(is_region_bundle_id("region-bundle:r.0.0"));
        assert!(!is_region_bundle_id(NON_REGION_PACK_ID));
    }
}
