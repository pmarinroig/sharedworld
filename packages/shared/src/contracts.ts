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
export type StorageLinkStatus = "pending" | "linked" | "expired" | "failed" | "cancelled";
export type StartupProgressMode = "determinate" | "indeterminate";
export type FileTransferMode = "whole-gzip" | "region-full" | "region-delta" | "pack-full" | "pack-delta";
export interface AuthChallenge {
  serverId: string;
  expiresAt: string;
}

export interface AuthCompleteRequest {
  serverId: string;
  playerName: string;
}

/**
 * Body for POST /auth/complete-cert: proves account ownership with the
 * Mojang-signed profile certificate (the 1.19+ chat-signing keypair) instead
 * of the sessionserver hasJoined flow, so the backend never has to reach
 * Mojang's sessionserver (which blocks Cloudflare Workers egress).
 */
export interface AuthCompleteCertRequest {
  serverId: string;
  /** 32 lowercase hex chars, no hyphens. Must match the certified profile. */
  playerUuid: string;
  /** Client-claimed display name (the certificate binds only the UUID). */
  playerName: string;
  /** Base64 X.509 SPKI DER of the profile public key. */
  publicKey: string;
  /**
   * Certificate expiry as epoch milliseconds — exactly the value Mojang's
   * signature covers (vanilla signs Instant.toEpochMilli, so an ISO string
   * round-trip could lose the signed precision).
   */
  publicKeyExpiresAtMs: number;
  /** Base64 Mojang signature (SHA1withRSA) over (uuid, expiry, publicKey). */
  keySignature: string;
  /** Base64 client signature (SHA256withRSA) over the serverId nonce bytes. */
  nonceSignature: string;
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

export type WorldDifficulty = "peaceful" | "easy" | "normal" | "hard";
export type WorldDefaultGameMode = "survival" | "creative" | "adventure";
/** SharedWorld's own gamerule ids; each version bucket maps them onto that Minecraft version's rules. */
export type WorldGameRule = "keepInventory" | "mobGriefing" | "daylightCycle" | "weatherCycle" | "pvp";

/**
 * Owner-chosen world settings. Absent fields mean "no override": the world
 * keeps whatever its level.dat already says. Applied by the active host's
 * client on its running server, so they take effect while playing and are
 * persisted by the normal world save.
 */
export interface WorldSettings {
  difficulty?: WorldDifficulty;
  defaultGameMode?: WorldDefaultGameMode;
  gamerules?: Partial<Record<WorldGameRule, boolean>>;
}

export interface UpdateWorldSettingsRequest {
  settings: WorldSettings;
}

/** The caller's linked storage account, if any (provider-level, reused across worlds). */
export interface StorageAccountSummary {
  linked: boolean;
  provider: StorageProviderType;
  email: string | null;
  displayName: string | null;
  /** False when the stored authorization can no longer refresh; relinking is required. */
  healthy: boolean;
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
  lastSnapshotDataVersion: number | null;
  lastSnapshotMinecraftVersion: string | null;
  settings: WorldSettings | null;
  settingsRevision: number;
}

export interface WorldMembership {
  worldId: string;
  playerUuid: string;
  playerName: string;
  role: MembershipRole;
  joinedAt: string;
  deletedAt: string | null;
  canUseCommands: boolean;
}

export interface UpdateMemberPermissionsRequest {
  canUseCommands: boolean;
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
  /** Bind the world to the caller's already-linked storage account instead of a fresh link session. */
  useLinkedStorageAccount?: boolean;
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
  runtimeEpoch: number;
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
  hostMinecraftVersion: string | null;
  /**
   * Server-suggested guest runtime poll interval (remote throttle lever).
   * Optional and additive: absent unless the deployment sets
   * SUGGESTED_RUNTIME_POLL_INTERVAL_MS. Clients clamp to their own safe
   * bounds and never poll faster than their built-in default.
   */
  suggestedPollIntervalMs?: number;
}

export interface HostHeartbeatMembership {
  playerUuid: string;
  playerName: string;
  canUseCommands: boolean;
}

/**
 * Host heartbeat response: a FLAT superset of WorldRuntimeStatus. Older mod
 * clients bind this to WorldRuntimeStatus and ignore the extra fields, so the
 * membership and settings data must stay at the top level (never nested
 * inside a wrapper object).
 */
export interface HostHeartbeatResponse extends WorldRuntimeStatus {
  memberships: HostHeartbeatMembership[];
  settings: WorldSettings | null;
  settingsRevision: number;
  /**
   * Server-suggested host cadences (remote throttle levers). Optional and
   * additive: absent unless the deployment sets the matching env vars.
   * Clients clamp to safe bounds below their liveness timeouts.
   */
  suggestedHeartbeatIntervalMs?: number;
  suggestedAutosaveIntervalMs?: number;
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

export interface HeartbeatRequest {
  runtimeEpoch?: number | null;
  hostToken?: string | null;
  joinTarget?: string | null;
  minecraftVersion?: string | null;
}

/**
 * Host-reported gamerule values, pushed when a command-permitted player
 * changes a managed rule in game. Runtime-authorized (epoch + host token),
 * so a non-owner host can persist changes. Only gamerules travel this path;
 * difficulty and default game mode stay owner-only.
 */
export interface HostGameRulesReportRequest {
  runtimeEpoch?: number | null;
  hostToken?: string | null;
  gamerules: Partial<Record<WorldGameRule, boolean>>;
}

export interface HostGameRulesReportResponse {
  /** The stored settings after the merge (source of truth for the host). */
  settings: WorldSettings;
  settingsRevision: number;
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

/**
 * Presence heartbeat response. Older mod clients never parse this body, so
 * every field here is additive by construction.
 */
export interface PresenceHeartbeatResponse {
  worldId: string;
  present: boolean;
  updatedAt: string;
  expiresAt: string;
  /** Server-suggested presence heartbeat interval (remote throttle lever). */
  suggestedIntervalMs?: number;
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
  dataVersion: number | null;
  minecraftVersion: string | null;
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
  /**
   * Pack ids of the latest snapshot. Lets a client prove "nothing changed"
   * (every pack alreadyPresent AND the pack id set matches) and skip the
   * finalize call entirely; without this a removed local pack would be
   * indistinguishable from no change. Additive — old clients ignore it.
   */
  latestPackIds?: string[];
}

export interface FinalizeSnapshotRequest {
  runtimeEpoch?: number | null;
  hostToken?: string | null;
  baseSnapshotId?: string | null;
  dataVersion?: number | null;
  minecraftVersion?: string | null;
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
  /** Force the full Google consent screen (used to recover an account whose stored authorization broke). */
  forceConsent?: boolean;
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
