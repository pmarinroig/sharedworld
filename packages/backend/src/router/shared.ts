import type { UploadPlanRequest } from "../../../shared/src/index.ts";

import { HttpError } from "../http.ts";
import type { RequestContext } from "../repository.ts";
import type { SharedWorldService } from "../service.ts";

export type RouterService = Pick<
  SharedWorldService,
  | "beginFinalization"
  | "cancelWaiting"
  | "completeAuth"
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
  | "refreshWaiting"
  | "releaseHost"
  | "resetInvite"
  | "restoreSnapshot"
  | "runtimeStatus"
  | "setHostStartupProgress"
  | "setPlayerPresence"
  | "updateWorld"
  | "uploadStorageBlob"
>;

type AuthenticatedRouterService = Pick<RouterService, "getSession">;

export type RouteMatch = {
  pathname: {
    groups: Record<string, string>;
  };
};

export type UrlPatternLike = {
  exec(input: string): RouteMatch | null;
};

type UrlPatternCtor = new (init: { pathname: string }) => UrlPatternLike;

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
  if (!files) {
    return {
      files: [],
      nonRegionPack: pack ? parseJsonHeader<UploadPlanRequest["nonRegionPack"]>(pack) : null,
      regionBundles: regionBundles ? parseJsonHeader<NonNullable<UploadPlanRequest["regionBundles"]>>(regionBundles) : []
    };
  }
  try {
    return {
      files: parseJsonHeader<UploadPlanRequest["files"]>(files),
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

export function renderStorageLinkPage(options: {
  status: number;
  tone: "success" | "error";
  title: string;
  message: string;
  linkedAccountEmail: string | null;
}): Response {
  const accentSoft = options.tone === "success" ? "rgba(92, 127, 104, 0.12)" : "rgba(155, 95, 95, 0.12)";
  const accountMarkup = options.linkedAccountEmail
    ? `
      <div class="account">
        <p class="account-value">${escapeHtml(options.linkedAccountEmail)}</p>
      </div>
    `
    : "";
  const html = `<!doctype html>
<html lang="en">
  <head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>SharedWorld</title>
    <style>
      :root {
        color-scheme: light;
      }

      * {
        box-sizing: border-box;
      }

      body {
        margin: 0;
        min-height: 100vh;
        font-family: "Avenir Next", "Segoe UI", sans-serif;
        background:
          radial-gradient(circle at top, rgba(123, 161, 144, 0.16), transparent 36%),
          linear-gradient(180deg, #eef3f1 0%, #f6f1e9 100%);
        color: #1f2933;
      }

      main {
        min-height: 100vh;
        display: grid;
        place-items: center;
        padding: 24px;
      }

      .shell {
        width: min(100%, 520px);
      }

      .brand {
        margin: 0 0 16px;
        text-align: center;
        font-size: 0.72rem;
        font-weight: 600;
        letter-spacing: 0.18em;
        text-transform: uppercase;
        color: #6d7c76;
      }

      .card {
        border-radius: 24px;
        border: 1px solid rgba(95, 111, 104, 0.14);
        background: rgba(255, 255, 255, 0.82);
        box-shadow: 0 24px 60px rgba(31, 41, 51, 0.1);
        padding: 36px 32px 32px;
        backdrop-filter: blur(8px);
      }

      h1 {
        margin: 0;
        font-size: clamp(2rem, 4vw, 2.4rem);
        line-height: 1.08;
        color: #14212b;
      }

      .message {
        margin: 14px 0 0;
        font-size: 1rem;
        line-height: 1.55;
        color: #556471;
      }

      .account {
        margin-top: 22px;
        padding-top: 18px;
        border-top: 1px solid ${accentSoft};
      }

      .account-value {
        margin: 0;
        font-size: 1.05rem;
        font-weight: 600;
        line-height: 1.5;
        color: #14212b;
        overflow-wrap: anywhere;
      }

      @media (max-width: 640px) {
        .card {
          padding: 28px 24px 24px;
          border-radius: 20px;
        }
      }
    </style>
  </head>
  <body>
    <main>
      <div class="shell">
        <p class="brand">SharedWorld</p>
        <section class="card">
          <h1>${escapeHtml(options.title)}</h1>
          ${accountMarkup}
          <p class="message">${escapeHtml(options.message)}</p>
        </section>
      </div>
    </main>
  </body>
</html>`;

  return new Response(html, {
    status: options.status,
    headers: {
      "content-type": "text/html; charset=utf-8"
    }
  });
}

class FallbackURLPattern implements UrlPatternLike {
  private readonly regex: RegExp;
  private readonly groupNames: string[];

  constructor(init: { pathname: string }) {
    const compiled = compilePathPattern(init.pathname);
    this.regex = compiled.regex;
    this.groupNames = compiled.groupNames;
  }

  exec(input: string): RouteMatch | null {
    const url = new URL(input);
    const match = this.regex.exec(url.pathname);
    if (!match) {
      return null;
    }
    const groups: Record<string, string> = {};
    for (const [index, name] of this.groupNames.entries()) {
      groups[name] = match[index + 1] ?? "";
    }
    return {
      pathname: {
        groups
      }
    };
  }
}

const globalUrlPattern = (globalThis as typeof globalThis & { URLPattern?: UrlPatternCtor }).URLPattern;

export const UrlPattern: UrlPatternCtor = globalUrlPattern ?? FallbackURLPattern;

function compilePathPattern(pathname: string): { regex: RegExp; groupNames: string[] } {
  const groupNames: string[] = [];
  const escapedSegments = pathname.split("/").map((segment) => {
    if (!segment.startsWith(":")) {
      return escapeRegex(segment);
    }
    const wildcard = segment.endsWith("*");
    const name = wildcard ? segment.slice(1, -1) : segment.slice(1);
    groupNames.push(name);
    return wildcard ? "(.*)" : "([^/]+)";
  });
  return {
    regex: new RegExp(`^${escapedSegments.join("/")}$`),
    groupNames
  };
}

function escapeRegex(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function escapeHtml(value: string): string {
  return value
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}
