import { createRouter } from "../../../src/router.ts";
import { MemorySharedWorldRepository } from "../../../src/memory-repository.ts";
import { SharedWorldService, WorkerSignedUrlSigner, type AuthVerifier } from "../../../src/service.ts";
import type { Env } from "../../../src/env.ts";
import type { StorageBinding, StorageProvider, StorageQuota, StoredBlob } from "../../../src/storage.ts";

class SimpleURLPattern {
  private readonly regex: RegExp;
  private readonly groupNames: string[];

  constructor(init: { pathname: string }) {
    const { regex, groupNames } = compilePathPattern(init.pathname);
    this.regex = regex;
    this.groupNames = groupNames;
  }

  exec(input: string) {
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

const globalScope = globalThis as typeof globalThis & { URLPattern?: typeof SimpleURLPattern };
if (globalScope.URLPattern === undefined) {
  globalScope.URLPattern = SimpleURLPattern;
}

interface StoredEntry {
  bytes: Uint8Array;
  contentType: string;
}

class FakeGoogleDriveStorageProvider implements StorageProvider {
  readonly provider = "google-drive" as const;
  private readonly entries = new Map<string, StoredEntry>();

  async exists(_binding: StorageBinding, storageKey: string): Promise<boolean> {
    return this.entries.has(storageKey);
  }

  async put(
    _binding: StorageBinding,
    storageKey: string,
    body: ReadableStream | ArrayBuffer | Uint8Array | string,
    contentType: string
  ): Promise<void> {
    this.entries.set(storageKey, {
      bytes: await toUint8Array(body),
      contentType
    });
  }

  async get(_binding: StorageBinding, storageKey: string): Promise<StoredBlob | null> {
    const entry = this.entries.get(storageKey);
    if (!entry) {
      return null;
    }
    const bytes = entry.bytes.slice();
    return {
      body: new ReadableStream({
        start(controller) {
          controller.enqueue(bytes);
          controller.close();
        }
      }),
      contentType: entry.contentType,
      size: bytes.byteLength,
      async arrayBuffer() {
        return bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
      }
    };
  }

  async delete(_binding: StorageBinding, storageKey: string): Promise<void> {
    this.entries.delete(storageKey);
  }

  async quota(): Promise<StorageQuota> {
    return {
      usedBytes: [...this.entries.values()].reduce((total, entry) => total + entry.bytes.byteLength, 0),
      totalBytes: null
    };
  }

  snapshot() {
    return {
      provider: this.provider,
      objects: [...this.entries.entries()]
        .map(([storageKey, entry]) => ({
          storageKey,
          contentType: entry.contentType,
          size: entry.bytes.byteLength
        }))
        .sort((left, right) => left.storageKey.localeCompare(right.storageKey))
    };
  }
}

interface IntegrationState {
  env: Env;
  storageProvider: FakeGoogleDriveStorageProvider;
  service: SharedWorldService;
}

const DEV_AUTH_SECRET = "test-dev-auth-secret";

export function createIntegrationTestApp(publicBaseUrl: string) {
  let state = createState(publicBaseUrl);

  return {
    reset() {
      state = createState(publicBaseUrl);
    },

    storageSnapshot() {
      return state.storageProvider.snapshot();
    },

    async fetch(request: Request): Promise<Response> {
      const url = new URL(request.url);
      if (url.pathname === "/__test/health") {
        return Response.json({ status: "ok" });
      }
      if (url.pathname === "/__test/reset" && request.method === "POST") {
        this.reset();
        return Response.json({ status: "reset" });
      }
      if (url.pathname === "/__test/storage") {
        return Response.json(this.storageSnapshot());
      }

      return createRouter(state.service)(request);
    }
  };
}

function createState(publicBaseUrl: string): IntegrationState {
  const env: Env = {
    PUBLIC_BASE_URL: publicBaseUrl,
    SIGNING_SECRET: "sharedworld-integration-secret",
    SESSION_TTL_HOURS: "24",
    ALLOW_DEV_AUTH: "true",
    ALLOW_DEV_INSECURE_E4MC: "true",
    DEV_AUTH_SECRET,
    ALLOW_DEV_GOOGLE_OAUTH: "true",
    DEV_GOOGLE_EMAIL: "integration-drive@example.com"
  };
  const repository = new MemorySharedWorldRepository();
  const storageProvider = new FakeGoogleDriveStorageProvider();
  const authVerifier: AuthVerifier = {
    async verifyJoin() {
      throw new Error("integration backend expected developer auth");
    }
  };
  return {
    env,
    storageProvider,
    service: new SharedWorldService(
      repository,
      authVerifier,
      new WorkerSignedUrlSigner(env),
      storageProvider,
      env
    )
  };
}

async function toUint8Array(body: ReadableStream | ArrayBuffer | Uint8Array | string): Promise<Uint8Array> {
  if (body instanceof Uint8Array) {
    return body.slice();
  }
  if (body instanceof ArrayBuffer) {
    return new Uint8Array(body);
  }
  if (typeof body === "string") {
    return new TextEncoder().encode(body);
  }
  const response = new Response(body);
  return new Uint8Array(await response.arrayBuffer());
}

function compilePathPattern(pathname: string): { regex: RegExp; groupNames: string[] } {
  const groupNames: string[] = [];
  const escapedSegments = pathname
    .split("/")
    .map((segment) => {
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
