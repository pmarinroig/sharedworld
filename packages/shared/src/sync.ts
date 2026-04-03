import type {
  DownloadPlan,
  DownloadPlanEntry,
  DownloadPackPlan,
  DownloadPlanStep,
  FileTransferMode,
  LocalFileDescriptor,
  LocalPackDescriptor,
  ManifestFile,
  SnapshotManifest,
  SnapshotPack,
  SyncPolicy,
  UploadPlan,
  UploadPackPlan,
  UploadPlanEntry
} from "./contracts.ts";

export const DEFAULT_SYNC_POLICY: SyncPolicy = {
  maxParallelDownloads: 4,
  maxConcurrentUploadPreparations: 1,
  maxConcurrentUploads: 1,
  maxUploadStartsPerSecond: 1,
  retryBaseDelayMs: 500,
  retryMaxDelayMs: 5_000
};

export const WHOLE_GZIP_TRANSFER_MODE: FileTransferMode = "whole-gzip";
export const REGION_FULL_TRANSFER_MODE: FileTransferMode = "region-full";
export const REGION_DELTA_TRANSFER_MODE: FileTransferMode = "region-delta";
export const PACK_FULL_TRANSFER_MODE: FileTransferMode = "pack-full";
export const PACK_DELTA_TRANSFER_MODE: FileTransferMode = "pack-delta";
export const REGION_DELTA_MIN_SAVINGS_RATIO = 0.1;
export const PACK_DELTA_MIN_SAVINGS_RATIO = 0.1;
export const MAX_REGION_DELTA_CHAIN_DEPTH = 12;
export const MAX_PACK_DELTA_CHAIN_DEPTH = 16;
export const NON_REGION_PACK_ID = "non-region";
export const REGION_BUNDLE_MAX_BYTES = 40 * 1024 * 1024;
export const REGION_BUNDLE_MAX_MEMBERS = 4;

export function isMcaFilePath(path: string): boolean {
  return path.toLowerCase().endsWith(".mca");
}

export function isTerrainRegionFilePath(path: string): boolean {
  const normalized = path.replace(/\\/g, "/").toLowerCase();
  return normalized.endsWith(".mca") && /(^|\/)region\/r\.-?\d+\.-?\d+\.mca$/i.test(normalized);
}

export function isBundledIntoSuperpack(path: string): boolean {
  return !isTerrainRegionFilePath(path);
}

export function isRegionBundleId(id: string): boolean {
  return id.startsWith("region-bundle:");
}

export interface RegionBundleMember {
  path: string;
  size: number;
}

export interface RegionBundleGroup {
  bundleId: string;
  paths: string[];
}

export function storageKeyForHash(hash: string): string {
  return `blobs/${hash.slice(0, 2)}/${hash}.bin`;
}

export function storageKeyForRegionFull(hash: string): string {
  return `regions/full/${hash.slice(0, 2)}/${hash}.mca`;
}

export function storageKeyForRegionDelta(baseHash: string, hash: string): string {
  return `regions/delta/${baseHash.slice(0, 2)}/${baseHash}-${hash}.bin`;
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

export function regionBundleIdForPath(path: string): string {
  const normalized = path.replace(/\\/g, "/");
  const match = normalized.match(/^(?:(.*)\/)?region\/r\.(-?\d+)\.(-?\d+)\.mca$/i);
  if (!match) {
    throw new Error(`Path ${path} is not a terrain region file.`);
  }
  const prefix = match[1];
  const directory = prefix ? `${prefix}/region` : "region";
  const x = Number.parseInt(match[2] ?? "", 10);
  const z = Number.parseInt(match[3] ?? "", 10);
  const tileX = Math.floor((x - 1) / 2) * 2 + 1;
  const tileZ = Math.floor((z - 1) / 2) * 2 + 1;
  return `region-bundle:${directory}:${tileX}:${tileZ}`;
}

export function groupTerrainRegionMembers(members: RegionBundleMember[]): RegionBundleGroup[] {
  const byBase = new Map<string, RegionBundleMember[]>();
  for (const member of members) {
    const bundleId = regionBundleIdForPath(member.path);
    const group = byBase.get(bundleId);
    if (group) {
      group.push(member);
    } else {
      byBase.set(bundleId, [member]);
    }
  }

  const groups: RegionBundleGroup[] = [];
  for (const [bundleId, groupMembers] of [...byBase.entries()].sort(([a], [b]) => a.localeCompare(b))) {
    const sortedMembers = [...groupMembers].sort((a, b) => a.path.localeCompare(b.path));
    splitRegionBundleGroup(bundleId, sortedMembers, groups);
  }
  return groups;
}

function splitRegionBundleGroup(bundleId: string, members: RegionBundleMember[], output: RegionBundleGroup[]): void {
  const totalSize = members.reduce((sum, member) => sum + member.size, 0);
  if (members.length <= 1 || (members.length <= REGION_BUNDLE_MAX_MEMBERS && totalSize <= REGION_BUNDLE_MAX_BYTES)) {
    output.push({ bundleId, paths: members.map((member) => member.path) });
    return;
  }

  if (members.length === 2) {
    for (const member of members) {
      output.push({ bundleId: `${bundleId}:${basenameWithoutExtension(member.path)}`, paths: [member.path] });
    }
    return;
  }

  const midpoint = Math.ceil(members.length / 2);
  splitRegionBundleGroup(`${bundleId}:a`, members.slice(0, midpoint), output);
  splitRegionBundleGroup(`${bundleId}:b`, members.slice(midpoint), output);
}

function basenameWithoutExtension(path: string): string {
  const normalized = path.replace(/\\/g, "/");
  const fileName = normalized.slice(normalized.lastIndexOf("/") + 1);
  return fileName.endsWith(".mca") ? fileName.slice(0, -4) : fileName;
}

export function diffUploads(
  localFiles: LocalFileDescriptor[],
  manifest: SnapshotManifest | null
): UploadPlanEntry[] {
  const byPath = new Map(manifest?.files.map((file) => [file.path, file]) ?? []);
  return localFiles.map((file) => {
    const existing = byPath.get(file.path);
    const alreadyPresent = existing?.hash === file.hash;
    return {
      file,
      storageKey: alreadyPresent
        ? existing?.storageKey ?? storageKeyForLocalFile(file)
        : storageKeyForLocalFile(file),
      transferMode: alreadyPresent
        ? existing?.transferMode ?? transferModeForLocalFile(file)
        : transferModeForLocalFile(file),
      alreadyPresent,
      baseSnapshotId: alreadyPresent ? existing?.baseSnapshotId ?? null : null,
      baseHash: alreadyPresent ? existing?.baseHash ?? null : null,
      baseChainDepth: alreadyPresent ? existing?.chainDepth ?? null : null
    };
  });
}

export function diffPackUpload(
  localPack: LocalPackDescriptor | null,
  manifest: SnapshotManifest | null
): UploadPackPlan | null {
  if (!localPack) {
    return null;
  }

  const existing = manifest?.packs.find((pack) => pack.packId === localPack.packId) ?? null;
  const alreadyPresent = existing?.hash === localPack.hash;
  return {
    pack: localPack,
    alreadyPresent,
    storageKey: alreadyPresent
      ? existing?.storageKey ?? storageKeyForPackFull(localPack.hash)
      : storageKeyForPackFull(localPack.hash),
    transferMode: alreadyPresent
      ? existing?.transferMode ?? PACK_FULL_TRANSFER_MODE
      : PACK_FULL_TRANSFER_MODE,
    baseSnapshotId: alreadyPresent ? existing?.baseSnapshotId ?? null : null,
    baseHash: alreadyPresent ? existing?.baseHash ?? null : null,
    baseChainDepth: alreadyPresent ? existing?.chainDepth ?? null : null
  };
}

export function diffBundleUploads(
  localBundles: LocalPackDescriptor[],
  manifest: SnapshotManifest | null
): UploadPackPlan[] {
  return localBundles.map((bundle) => {
    const existing = manifest?.packs.find((pack) => pack.packId === bundle.packId) ?? null;
    const alreadyPresent = existing?.hash === bundle.hash;
    return {
      pack: bundle,
      alreadyPresent,
      storageKey: alreadyPresent
        ? existing?.storageKey ?? storageKeyForRegionFull(bundle.hash)
        : storageKeyForRegionFull(bundle.hash),
      transferMode: alreadyPresent
        ? existing?.transferMode ?? REGION_FULL_TRANSFER_MODE
        : REGION_FULL_TRANSFER_MODE,
      baseSnapshotId: alreadyPresent ? existing?.baseSnapshotId ?? null : null,
      baseHash: alreadyPresent ? existing?.baseHash ?? null : null,
      baseChainDepth: alreadyPresent ? existing?.chainDepth ?? null : null
    };
  });
}

export function diffDownloads(
  localFiles: LocalFileDescriptor[],
  manifest: SnapshotManifest | null
): DownloadPlan {
  if (!manifest) {
    return {
      worldId: "",
      snapshotId: null,
      downloads: [],
      nonRegionPackDownload: null,
      regionBundleDownloads: [],
      retainedPaths: localFiles.map((file) => file.path),
      syncPolicy: DEFAULT_SYNC_POLICY
    };
  }

  const localByPath = new Map(localFiles.map((file) => [file.path, file]));
  const downloads: DownloadPlanEntry[] = [];
  const retainedPaths: string[] = [];
  let nonRegionPackDownload: DownloadPackPlan | null = null;
  const regionBundleDownloads: DownloadPackPlan[] = [];

  for (const file of manifest.files) {
    const local = localByPath.get(file.path);
    if (local?.hash === file.hash) {
      retainedPaths.push(file.path);
      continue;
    }

    downloads.push(downloadEntryFromManifest(file));
  }

  for (const pack of manifest.packs) {
    const changedFiles = pack.files.filter((file) => localByPath.get(file.path)?.hash !== file.hash);
    if (changedFiles.length === 0) {
      retainedPaths.push(...pack.files.map((file) => file.path));
      continue;
    }
    if (pack.packId === NON_REGION_PACK_ID) {
      nonRegionPackDownload = downloadPackFromManifest(pack);
    } else {
      regionBundleDownloads.push(downloadPackFromManifest(pack));
    }
  }

  return {
    worldId: manifest.worldId,
    snapshotId: manifest.snapshotId,
    downloads,
    nonRegionPackDownload,
    regionBundleDownloads,
    retainedPaths,
    syncPolicy: DEFAULT_SYNC_POLICY
  };
}

export function manifestFileFromLocal(file: LocalFileDescriptor): ManifestFile {
  return {
    path: file.path,
    hash: file.hash,
    size: file.size,
    compressedSize: file.compressedSize,
    storageKey: storageKeyForLocalFile(file),
    contentType: file.contentType ?? "application/octet-stream",
    transferMode: transferModeForLocalFile(file),
    baseSnapshotId: null,
    baseHash: null,
    chainDepth: file.deltaCapable ? 0 : null
  };
}

export function buildUploadPlan(
  worldId: string,
  localFiles: LocalFileDescriptor[],
  manifest: SnapshotManifest | null,
  nonRegionPack: LocalPackDescriptor | null = null
): UploadPlan {
  return {
    worldId,
    snapshotBaseId: manifest?.snapshotId ?? null,
    uploads: diffUploads(localFiles, manifest),
    nonRegionPackUpload: diffPackUpload(nonRegionPack, manifest),
    regionBundleUploads: [],
    syncPolicy: DEFAULT_SYNC_POLICY
  };
}

export function snapshotPackFromLocal(pack: LocalPackDescriptor): SnapshotPack {
  return {
    packId: pack.packId,
    hash: pack.hash,
    size: pack.size,
    storageKey: storageKeyForPackFull(pack.hash),
    transferMode: PACK_FULL_TRANSFER_MODE,
    baseSnapshotId: null,
    baseHash: null,
    chainDepth: 0,
    files: pack.files
  };
}

function transferModeForLocalFile(file: LocalFileDescriptor): FileTransferMode {
  return file.deltaCapable ? REGION_FULL_TRANSFER_MODE : WHOLE_GZIP_TRANSFER_MODE;
}

function storageKeyForLocalFile(file: LocalFileDescriptor): string {
  return file.deltaCapable ? storageKeyForRegionFull(file.hash) : storageKeyForHash(file.hash);
}

function downloadEntryFromManifest(file: ManifestFile): DownloadPlanEntry {
  const step: DownloadPlanStep = {
    transferMode: file.transferMode ?? WHOLE_GZIP_TRANSFER_MODE,
    storageKey: file.storageKey,
    artifactSize: file.compressedSize,
    baseSnapshotId: file.baseSnapshotId ?? null,
    baseHash: file.baseHash ?? null,
    download: {
      method: "GET",
      url: "",
      headers: {},
      expiresAt: new Date(0).toISOString()
    }
  };
  return {
    path: file.path,
    hash: file.hash,
    size: file.size,
    contentType: file.contentType,
    steps: [step]
  };
}

function downloadPackFromManifest(pack: SnapshotPack): DownloadPackPlan {
  const step: DownloadPlanStep = {
    transferMode: pack.transferMode ?? PACK_FULL_TRANSFER_MODE,
    storageKey: pack.storageKey,
    artifactSize: pack.size,
    baseSnapshotId: pack.baseSnapshotId ?? null,
    baseHash: pack.baseHash ?? null,
    download: {
      method: "GET",
      url: "",
      headers: {},
      expiresAt: new Date(0).toISOString()
    }
  };
  return {
    packId: pack.packId,
    hash: pack.hash,
    size: pack.size,
    files: pack.files,
    steps: [step]
  };
}
