import type { SessionToken, UploadPlanRequest } from "../../../shared/src/index.ts";

import { HttpError } from "../http.ts";
import type { RequestContext } from "../repository.ts";
import type { SharedWorldService } from "../service.ts";

export type RouterService = Pick<
  SharedWorldService,
  | "beginFinalization"
  | "cancelWaiting"
  | "cancelStorageLink"
  | "completeAuth"
  | "completeCertAuth"
  | "completeDevAuth"
  | "completeFinalization"
  | "completeStorageLink"
  | "createChallenge"
  | "createInvite"
  | "createStorageLink"
  | "createWorld"
  | "abandonFinalization"
  | "deleteSnapshot"
  | "deleteSnapshots"
  | "deleteWorld"
  | "downloadPlan"
  | "downloadStorageBlob"
  | "enterSession"
  | "finalizeSnapshot"
  | "getSession"
  | "getStorageAccount"
  | "getStorageLinkSession"
  | "getStorageUsage"
  | "getWorld"
  | "heartbeatHost"
  | "kickMember"
  | "latestManifest"
  | "listSnapshots"
  | "connectRealtime"
  | "listWorlds"
  | "observeWaiting"
  | "prepareUploads"
  | "redeemInvite"
  | "releaseHost"
  | "reportHostGameRules"
  | "resetInvite"
  | "restoreSnapshot"
  | "runtimeStatus"
  | "setHostStartupProgress"
  | "setMemberCommandPermission"
  | "setPlayerPresence"
  | "updateWorld"
  | "updateWorldSettings"
  | "uploadStorageBlob"
  | "createBlobUploadSession"
  | "commitBlobUploadSession"
  | "worldsEtag"
  | "worldEtag"
>;

type AuthenticatedRouterService = Pick<RouterService, "getSession">;

import type { RouteMatch, UrlPatternLike } from "./url-pattern.ts";

export { FallbackURLPattern, UrlPattern } from "./url-pattern.ts";
export type { RouteMatch, UrlPatternLike } from "./url-pattern.ts";
export { renderStorageLinkPage } from "./link-result-page.ts";

export type Handler = (
  request: Request,
  params: RouteMatch["pathname"]["groups"],
  ctx: RequestContext
) => Promise<Response>;

export type RouteDefinition = {
  method: string;
  pattern: UrlPatternLike;
  handler: Handler;
  auth?: boolean;
};

export function requireParam(value: string | undefined, name: string): string {
  if (!value) {
    throw new HttpError(400, "missing_param", `Missing URL parameter: ${name}.`);
  }
  return value;
}

/**
 * In-isolate session cache. Sessions are immutable after insert (there is no
 * revocation or deletion path anywhere), and expiry is checked from the
 * record's own expiresAt on every request below — so a cached row can never
 * grant access a fresh D1 read would deny. TTL bounds memory and keeps a
 * future revocation feature honest; the size cap guards runaway isolates.
 */
const SESSION_CACHE_TTL_MS = 5 * 60_000;
const SESSION_CACHE_MAX_ENTRIES = 512;
const sessionCache = new Map<string, { session: SessionToken; cachedAt: number }>();

export function clearSessionCache(): void {
  sessionCache.clear();
}

export async function authenticate(request: Request, service: AuthenticatedRouterService): Promise<RequestContext> {
  const header = request.headers.get("authorization");
  if (!header?.startsWith("Bearer ")) {
    throw new HttpError(401, "missing_auth", "Authorization header is required.");
  }
  const token = header.slice("Bearer ".length);
  const cached = sessionCache.get(token);
  let session = cached != null && Date.now() - cached.cachedAt < SESSION_CACHE_TTL_MS ? cached.session : null;
  if (session == null) {
    session = await service.getSession(token);
    if (session != null) {
      if (sessionCache.size >= SESSION_CACHE_MAX_ENTRIES) {
        sessionCache.clear();
      }
      sessionCache.set(token, { session, cachedAt: Date.now() });
    }
  }
  if (!session) {
    throw new HttpError(401, "invalid_session", "Session token is invalid.");
  }
  if (new Date(session.expiresAt).getTime() < Date.now()) {
    throw new HttpError(401, "expired_session", "Session token has expired.");
  }
  return {
    playerUuid: session.playerUuid,
    playerName: session.playerName,
    requestOrigin: new URL(request.url).origin,
    clientVersion: request.headers.get("x-sharedworld-version")
  };
}

export async function parseDownloadPlanRequest(request: Request): Promise<UploadPlanRequest> {
  const files = request.headers.get("x-sharedworld-files");
  const pack = request.headers.get("x-sharedworld-pack");
  const regionBundles = request.headers.get("x-sharedworld-region-bundles");
  try {
    return {
      files: files ? parseJsonHeader<UploadPlanRequest["files"]>(files) : [],
      nonRegionPack: pack ? parseJsonHeader<UploadPlanRequest["nonRegionPack"]>(pack) : null,
      regionBundles: regionBundles ? parseJsonHeader<NonNullable<UploadPlanRequest["regionBundles"]>>(regionBundles) : []
    };
  } catch {
    throw new HttpError(400, "invalid_download_plan_header", "download plan headers must be valid JSON.");
  }
}

export function decodeStorageKey(storageKey: string): string {
  try {
    return decodeURIComponent(storageKey);
  } catch {
    throw new HttpError(400, "invalid_storage_key", "Storage key is malformed.");
  }
}

function parseJsonHeader<T>(value: string): T {
  const parsed: unknown = JSON.parse(value);
  return parsed as T;
}



