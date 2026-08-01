import type { UploadPlanRequest } from "../../../shared/src/index.ts";

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
  | "deleteWorld"
  | "downloadPlan"
  | "downloadStorageBlob"
  | "enterSession"
  | "finalizeSnapshot"
  | "getSession"
  | "getStorageAccount"
  | "getStorageLinkSession"
  | "getWorld"
  | "heartbeatHost"
  | "kickMember"
  | "latestManifest"
  | "listSnapshots"
  | "listWorlds"
  | "observeWaiting"
  | "prepareUploads"
  | "redeemInvite"
  | "releaseHost"
  | "resetInvite"
  | "restoreSnapshot"
  | "runtimeStatus"
  | "setHostStartupProgress"
  | "setMemberCommandPermission"
  | "setPlayerPresence"
  | "updateWorld"
  | "updateWorldSettings"
  | "uploadStorageBlob"
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

export async function authenticate(request: Request, service: AuthenticatedRouterService): Promise<RequestContext> {
  const header = request.headers.get("authorization");
  if (!header?.startsWith("Bearer ")) {
    throw new HttpError(401, "missing_auth", "Authorization header is required.");
  }
  const token = header.slice("Bearer ".length);
  const session = await service.getSession(token);
  if (!session) {
    throw new HttpError(401, "invalid_session", "Session token is invalid.");
  }
  if (new Date(session.expiresAt).getTime() < Date.now()) {
    throw new HttpError(401, "expired_session", "Session token has expired.");
  }
  return {
    playerUuid: session.playerUuid,
    playerName: session.playerName,
    requestOrigin: new URL(request.url).origin
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



