export const HOST_HEARTBEAT_INTERVAL_MS = 30_000;
export const HOST_LEASE_TIMEOUT_MS = 90_000;
export const HANDOFF_WAITER_TIMEOUT_MS = 120_000;
export const PLAYER_PRESENCE_HEARTBEAT_INTERVAL_MS = 15_000;
export const PLAYER_PRESENCE_TIMEOUT_MS = 45_000;
export const AUTOSAVE_INTERVAL_MS = 5 * 60_000;
export const INVITE_TTL_MS = 7 * 24 * 60 * 60_000;
export const STORAGE_LINK_TTL_MS = 15 * 60_000;

export type WorldStatus = "idle" | "hosting" | "finalizing" | "handoff";
export type WorldRuntimePhase = "idle" | "host-starting" | "host-live" | "host-finalizing" | "handoff-waiting";
export type MembershipRole = "owner" | "member";
export type InviteStatus = "active" | "expired" | "revoked" | "redeemed";
export type EnterSessionAction = "connect" | "host" | "wait" | "warn-host";
export type ObserveWaitingAction = "connect" | "wait" | "restart";
export type StorageProviderType = "google-drive" | "r2";
export type StorageLinkStatus = "pending" | "linked" | "expired" | "failed";
export type StartupProgressMode = "determinate" | "indeterminate";
export type FileTransferMode = "whole-gzip" | "region-full" | "region-delta" | "pack-full" | "pack-delta";
export type RecoveryFlowKind = "join" | "handoff" | "disconnect-recovery";

export interface AuthChallenge {
  serverId: string;
  expiresAt: string;
}

export interface AuthCompleteRequest {
  serverId: string;
  playerName: string;
}

export interface DevAuthCompleteRequest {
  playerUuid: string;
  playerName: string;
  secret: string;
}

export interface SessionToken {
  token: string;
  playerUuid: string;
  playerName: string;
  expiresAt: string;
}

export interface DevSessionToken extends SessionToken {
  allowInsecureE4mc: boolean;
}

export interface SignedBlobUrl {
  method: "PUT" | "GET";
  url: string;
  headers: Record<string, string>;
  expiresAt: string;
}

export interface SyncPolicy {
  maxParallelDownloads: number;
  maxConcurrentUploadPreparations: number;
  maxConcurrentUploads: number;
  maxUploadStartsPerSecond: number;
  retryBaseDelayMs: number;
  retryMaxDelayMs: number;
}

export interface StorageUsageSummary {
  provider: StorageProviderType;
  linked: boolean;
  usedBytes: number;
  quotaUsedBytes: number | null;
  quotaTotalBytes: number | null;
  accountEmail: string | null;
}

export interface WorldSummary {
  id: string;
  slug: string;
  name: string;
  ownerUuid: string;
  motd: string | null;
  customIconStorageKey: string | null;
  customIconDownload: SignedBlobUrl | null;
  memberCount: number;
  status: WorldStatus;
  lastSnapshotId: string | null;
  lastSnapshotAt: string | null;
  activeHostUuid: string | null;
  activeHostPlayerName: string | null;
  activeJoinTarget: string | null;
  onlinePlayerCount: number;
  onlinePlayerNames: string[];
  storageProvider: StorageProviderType;
  storageLinked: boolean;
  storageAccountEmail: string | null;
}

export interface WorldMembership {
  worldId: string;
  playerUuid: string;
  playerName: string;
  role: MembershipRole;
  joinedAt: string;
  deletedAt: string | null;
}

export interface ImportedWorldSource {
  type: "local-save";
  id: string;
  name: string;
}

export interface CreateWorldRequest {
  name: string;
  motdLine1?: string | null;
  motdLine2?: string | null;
  customIconPngBase64?: string | null;
  importSource?: ImportedWorldSource | null;
  storageLinkSessionId?: string | null;
}

export interface UpdateWorldRequest {
  name: string;
  motdLine1?: string | null;
  motdLine2?: string | null;
  customIconPngBase64?: string | null;
  clearCustomIcon?: boolean;
}

export interface WorldDetails extends WorldSummary {
  membership: WorldMembership;
  memberships: WorldMembership[];
  storageUsage: StorageUsageSummary | null;
  activeInviteCode: InviteCode | null;
}

export interface CreateWorldResult {
  world: WorldDetails;
  initialUploadAssignment: HostAssignment;
}

export interface InviteCode {
  id: string;
  worldId: string;
  code: string;
  createdByUuid: string;
  createdAt: string;
  expiresAt: string;
  status: InviteStatus;
}

export interface ResetInviteResponse {
  revokedInviteIds: string[];
  invite: InviteCode;
}

export interface RedeemInviteRequest {
  code: string;
}

export interface HostStartupProgress {
  label: string;
  mode: StartupProgressMode;
  fraction: number | null;
  updatedAt: string;
}

export interface UncleanShutdownWarning {
  hostUuid: string;
  hostPlayerName: string;
  phase: "host-live" | "host-finalizing";
  recordedAt: string;
}

export interface HostAssignment {
  worldId: string;
  playerUuid: string;
  playerName: string;
  runtimeEpoch: number;
  hostToken: string;
  startupDeadlineAt: string | null;
}

export interface WorldRuntimeStatus {
  worldId: string;
  phase: WorldRuntimePhase;
  runtimeEpoch: number;
  hostUuid: string | null;
  hostPlayerName: string | null;
  candidateUuid: string | null;
  candidatePlayerName: string | null;
  joinTarget: string | null;
  startupDeadlineAt: string | null;
  runtimeTokenIssuedAt: string | null;
  lastProgressAt: string | null;
  updatedAt: string | null;
  revokedAt: string | null;
  startupProgress: HostStartupProgress | null;
  uncleanShutdownWarning: UncleanShutdownWarning | null;
}

export interface EnterSessionRequest {
  waiterSessionId?: string | null;
  acknowledgeUncleanShutdown?: boolean;
}

export interface EnterSessionResponse {
  action: EnterSessionAction;
  world: WorldSummary;
  latestManifest: SnapshotManifest | null;
  runtime: WorldRuntimeStatus;
  assignment: HostAssignment | null;
  waiterSessionId: string | null;
}

export interface RefreshWaitingRequest {
  waiterSessionId: string;
}

export interface CancelWaitingRequest {
  waiterSessionId: string;
}

export interface ObserveWaitingRequest {
  waiterSessionId: string;
}

export interface ObserveWaitingResponse {
  action: ObserveWaitingAction;
  runtime: WorldRuntimeStatus;
  assignment: HostAssignment | null;
  waiterSessionId: string | null;
}

export interface RecoveryRecord {
  worldId: string;
  worldName: string;
  runtimeEpoch: number;
  flowKind: RecoveryFlowKind;
  previousJoinTarget: string | null;
}

export interface HeartbeatRequest {
  runtimeEpoch?: number | null;
  hostToken?: string | null;
  joinTarget?: string | null;
}

export interface HostStartupProgressRequest {
  runtimeEpoch?: number | null;
  hostToken?: string | null;
  label?: string | null;
  mode?: StartupProgressMode | null;
  fraction?: number | null;
}

export interface PresenceHeartbeatRequest {
  present: boolean;
  guestSessionEpoch: number;
  presenceSequence: number;
}

export interface ReleaseHostRequest {
  snapshotId?: string | null;
  graceful: boolean;
  runtimeEpoch?: number | null;
  hostToken?: string | null;
}

export interface BeginFinalizationRequest {
  runtimeEpoch?: number | null;
  hostToken?: string | null;
}

export interface CompleteFinalizationRequest {
  runtimeEpoch?: number | null;
  hostToken?: string | null;
}

export interface AbandonFinalizationRequest {
  runtimeEpoch?: number | null;
  hostToken?: string | null;
}

export interface FinalizationActionResult {
  worldId: string;
  nextHostUuid: string | null;
  nextHostPlayerName: string | null;
  status: WorldStatus;
}

export interface SnapshotPublisherAuth {
  runtimeEpoch: number;
  hostToken: string;
}

export interface ManifestFile {
  path: string;
  hash: string;
  size: number;
  compressedSize: number;
  storageKey: string;
  contentType: string;
  transferMode?: FileTransferMode;
  baseSnapshotId?: string | null;
  baseHash?: string | null;
  chainDepth?: number | null;
}

export interface PackedManifestFile {
  path: string;
  hash: string;
  size: number;
  contentType: string;
}

export interface SnapshotPack {
  packId: string;
  hash: string;
  size: number;
  storageKey: string;
  transferMode: FileTransferMode;
  baseSnapshotId?: string | null;
  baseHash?: string | null;
  chainDepth?: number | null;
  files: PackedManifestFile[];
}

export interface SnapshotManifest {
  worldId: string;
  snapshotId: string;
  createdAt: string;
  createdByUuid: string;
  files: ManifestFile[];
  packs: SnapshotPack[];
}

export interface WorldSnapshotSummary {
  snapshotId: string;
  createdAt: string;
  createdByUuid: string;
  fileCount: number;
  totalSize: number;
  totalCompressedSize: number;
  isLatest: boolean;
}

export interface SnapshotActionResult {
  worldId: string;
  snapshotId: string;
}

export interface UploadPlanRequest {
  runtimeEpoch?: number | null;
  hostToken?: string | null;
  files: LocalFileDescriptor[];
  nonRegionPack?: LocalPackDescriptor | null;
  regionBundles?: LocalPackDescriptor[] | null;
}

export interface LocalFileDescriptor {
  path: string;
  hash: string;
  size: number;
  compressedSize: number;
  contentType?: string;
  deltaCapable: boolean;
}

export interface LocalPackDescriptor {
  packId: string;
  hash: string;
  size: number;
  fileCount: number;
  files: PackedManifestFile[];
}

export interface UploadPlanEntry {
  file: LocalFileDescriptor;
  alreadyPresent: boolean;
  storageKey?: string | null;
  transferMode?: FileTransferMode | null;
  upload?: SignedBlobUrl;
  fullStorageKey?: string | null;
  fullUpload?: SignedBlobUrl;
  deltaStorageKey?: string | null;
  deltaUpload?: SignedBlobUrl;
  baseSnapshotId?: string | null;
  baseHash?: string | null;
  baseChainDepth?: number | null;
}

export interface UploadPackPlan {
  pack: LocalPackDescriptor;
  alreadyPresent: boolean;
  storageKey?: string | null;
  transferMode?: FileTransferMode | null;
  upload?: SignedBlobUrl;
  fullStorageKey?: string | null;
  fullUpload?: SignedBlobUrl;
  deltaStorageKey?: string | null;
  deltaUpload?: SignedBlobUrl;
  baseSnapshotId?: string | null;
  baseHash?: string | null;
  baseChainDepth?: number | null;
}

export interface UploadPlan {
  worldId: string;
  snapshotBaseId: string | null;
  uploads: UploadPlanEntry[];
  nonRegionPackUpload?: UploadPackPlan | null;
  regionBundleUploads?: UploadPackPlan[];
  syncPolicy: SyncPolicy;
}

export interface FinalizeSnapshotRequest {
  runtimeEpoch?: number | null;
  hostToken?: string | null;
  baseSnapshotId?: string | null;
  files: ManifestFile[];
  packs?: SnapshotPack[];
}

export interface DownloadPlanStep {
  transferMode: FileTransferMode;
  storageKey: string;
  artifactSize: number;
  baseSnapshotId?: string | null;
  baseHash?: string | null;
  download: SignedBlobUrl;
}

export interface DownloadPlanEntry {
  path: string;
  hash: string;
  size: number;
  contentType: string;
  steps: DownloadPlanStep[];
}

export interface DownloadPackPlan {
  packId: string;
  hash: string;
  size: number;
  files: PackedManifestFile[];
  steps: DownloadPlanStep[];
}

export interface DownloadPlan {
  worldId: string;
  snapshotId: string | null;
  downloads: DownloadPlanEntry[];
  nonRegionPackDownload?: DownloadPackPlan | null;
  regionBundleDownloads?: DownloadPackPlan[];
  retainedPaths: string[];
  syncPolicy: SyncPolicy;
}

export interface CreateStorageLinkRequest {
  provider?: StorageProviderType;
  importSource?: ImportedWorldSource | null;
}

export interface StorageLinkSession {
  id: string;
  provider: StorageProviderType;
  status: StorageLinkStatus;
  authUrl: string;
  expiresAt: string;
  linkedAccountEmail: string | null;
  accountDisplayName: string | null;
  errorMessage: string | null;
}

export interface StorageLinkCompleteRequest {
  sessionId: string;
  code?: string | null;
  state?: string | null;
  mockEmail?: string | null;
}

export interface KickMemberResponse {
  worldId: string;
  removedPlayerUuid: string;
}

export interface ApiErrorShape {
  error: string;
  message: string;
  status: number;
}
