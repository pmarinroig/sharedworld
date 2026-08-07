import { generateKeyPairSync } from "node:crypto";

import { createRouter } from "../../../src/router.ts";
import { createSqliteRepository } from "../sqlite-d1.ts";
import { LocalRealtimeService } from "../realtime-local.ts";
import { SharedWorldService, WorkerSignedUrlSigner, type AuthVerifier } from "../../../src/service.ts";
import type { SharedWorldRepository } from "../../../src/repository.ts";
import type { Env } from "../../../src/env.ts";
import type { StorageBinding, StorageProvider, StorageQuota, StoredBlob } from "../../../src/storage.ts";


interface StoredEntry {
  bytes: Uint8Array;
  contentType: string;
}

/**
 * Mirrors the real GoogleDriveStorageProvider contract: every stored object is
 * also recorded as a storage_objects row, which the backend uses for snapshot
 * validation and storage usage accounting.
 */
class FakeGoogleDriveStorageProvider implements StorageProvider {
  readonly provider = "google-drive" as const;
  private readonly entries = new Map<string, StoredEntry>();

  constructor(private readonly repository: SharedWorldRepository) {}

  async exists(binding: StorageBinding, storageKey: string): Promise<boolean> {
    return this.entries.has(storageKey);
  }

  async put(
    binding: StorageBinding,
    storageKey: string,
    body: ReadableStream | ArrayBuffer | Uint8Array | string,
    contentType: string
  ): Promise<void> {
    const bytes = await toUint8Array(body);
    this.entries.set(storageKey, {
      bytes,
      contentType
    });
    if (binding.storageAccountId != null) {
      await this.repository.upsertStorageObject({
        provider: this.provider,
        storageAccountId: binding.storageAccountId,
        storageKey,
        objectId: `fake-${storageKey}`,
        contentType,
        size: bytes.byteLength,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString()
      });
    }
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

  async delete(binding: StorageBinding, storageKey: string): Promise<void> {
    this.entries.delete(storageKey);
    if (binding.storageAccountId != null) {
      await this.repository.deleteStorageObject(this.provider, binding.storageAccountId, storageKey);
    }
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
  realtime: LocalRealtimeService;
  service: SharedWorldService;
}

const DEV_AUTH_SECRET = "test-dev-auth-secret";

// A stand-in for Mojang's player-certificate services key: the public half is
// pinned via MOJANG_PLAYER_CERTIFICATE_KEYS, the private half is served on a
// __test route so the Java integration test can forge a realistic certificate
// — the one place a Java-signed request meets the real TS verifier.
const servicesKeyPair = generateKeyPairSync("rsa", { modulusLength: 2048 });
const servicesPublicKeyB64 = servicesKeyPair.publicKey.export({ type: "spki", format: "der" }).toString("base64");
const servicesPrivateKeyPkcs8B64 = servicesKeyPair.privateKey.export({ type: "pkcs8", format: "der" }).toString("base64");

export function createIntegrationTestApp(publicBaseUrl: string) {
  let state = createState(publicBaseUrl);

  return {
    reset() {
      state = createState(publicBaseUrl);
    },

    storageSnapshot() {
      return state.storageProvider.snapshot();
    },

    /** Current realtime service (fresh after each reset) for the WS bridge. */
    realtime() {
      return state.realtime;
    },

    /** Session lookup for the WS bridge's upgrade authentication. */
    getSession(token: string) {
      return state.service.getSession(token);
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
      if (url.pathname === "/__test/cert-signing-key") {
        return Response.json({ privateKeyPkcs8: servicesPrivateKeyPkcs8B64 });
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
    DEV_GOOGLE_EMAIL: "integration-drive@example.com",
    MOJANG_PLAYER_CERTIFICATE_KEYS: servicesPublicKeyB64
  };
  const repository = createSqliteRepository();
  const storageProvider = new FakeGoogleDriveStorageProvider(repository);
  const authVerifier: AuthVerifier = {
    async verifyJoin() {
      throw new Error("integration backend expected developer auth");
    }
  };
  // In-process realtime: real coordinator logic per world. The integration
  // server bridges realtime.onPublish to its WebSocket clients.
  const realtime = new LocalRealtimeService(repository);
  return {
    env,
    storageProvider,
    realtime,
    service: new SharedWorldService(
      repository,
      authVerifier,
      new WorkerSignedUrlSigner(env),
      storageProvider,
      env,
      realtime
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

