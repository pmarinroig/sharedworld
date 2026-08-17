import { generateKeyPairSync } from "node:crypto";
import { appendFileSync, existsSync, mkdirSync, readFileSync, renameSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";

import { createRouter } from "../../../src/router.ts";
import { HttpError } from "../../../src/http.ts";
import { createSqliteRepository } from "../sqlite-d1.ts";
import { LocalRealtimeService } from "../realtime-local.ts";
import { SharedWorldService, WorkerSignedUrlSigner } from "../../../src/service.ts";
import type { SharedWorldRepository } from "../../../src/repository.ts";
import type { Env } from "../../../src/env.ts";
import { providerManifestDocumentReader } from "../../../src/manifest-doc.ts";
import type { BlobRange, ResumableProbe, StorageBinding, StorageProvider, StorageQuota, StoredBlob } from "../../../src/storage.ts";


interface StoredEntry {
  contentType: string;
  objectId: string;
  size: number;
  /** Memory mode: the blob bytes. */
  bytes?: Uint8Array;
  /** Disk mode: file under blobDir holding the blob bytes. */
  fileName?: string;
}

interface FakeResumableSession {
  fileId: string;
  storageKey: string;
  storageAccountId: string | null;
  contentType: string;
  expectedSize: number;
  received: number;
  chunkPuts: number;
  completed: boolean;
  /** Disk mode: temp file accumulating the chunk bytes. */
  tempFileName?: string;
  /** Memory mode: accumulated chunk bytes. */
  parts?: Uint8Array[];
}

/**
 * Mirrors the real GoogleDriveStorageProvider contract: every stored object is
 * also recorded as a storage_objects row, which the backend uses for snapshot
 * validation and storage usage accounting.
 *
 * Also implements ResumableUploadCapable with faithful Drive session-protocol
 * semantics (chunk PUTs with Content-Range, 308 + Range high-water responses,
 * "bytes STAR/N" status probes) served on /__fake-drive/upload/:id — so real
 * 0.4.0 clients exercise the direct-to-Drive upload path against the
 * integration backend instead of silently falling back to the relay.
 *
 * With blobDir set, blob bytes live on disk (multi-GB worlds must not live in
 * this process's memory) and the entry index persists to index.json so a
 * harness restart is deploy-faithful alongside the file-backed D1. Upload
 * sessions are intentionally memory-only: a restart expires them, which is
 * exactly what the client's session-gone recovery path expects.
 */
class FakeGoogleDriveStorageProvider implements StorageProvider {
  readonly provider = "google-drive" as const;
  private readonly entries = new Map<string, StoredEntry>();
  private readonly sessions = new Map<string, FakeResumableSession>();
  /** Relay download counts per storage key, for orchestrator assertions (e.g. "the delta sync never re-fetched the full blob"). */
  private readonly downloadCounts = new Map<string, number>();
  private sessionCounter = 0;

  constructor(
    private readonly repository: SharedWorldRepository,
    private readonly publicBaseUrl: string,
    private readonly blobDir: string | null
  ) {
    if (this.blobDir) {
      mkdirSync(this.blobDir, { recursive: true });
      const indexPath = join(this.blobDir, "index.json");
      if (existsSync(indexPath)) {
        const index = JSON.parse(readFileSync(indexPath, "utf8")) as Array<[string, StoredEntry]>;
        for (const [storageKey, entry] of index) {
          this.entries.set(storageKey, entry);
        }
      }
    }
  }

  private persistIndex(): void {
    if (this.blobDir) {
      writeFileSync(join(this.blobDir, "index.json"), JSON.stringify([...this.entries.entries()]));
    }
  }

  private blobPath(fileName: string): string {
    if (!this.blobDir) {
      throw new Error("blobPath requires disk mode");
    }
    return join(this.blobDir, fileName);
  }

  async exists(binding: StorageBinding, storageKey: string): Promise<boolean> {
    return this.entries.has(storageKey);
  }

  async put(
    binding: StorageBinding,
    storageKey: string,
    body: ReadableStream | ArrayBuffer | Uint8Array | string,
    contentType: string
  ): Promise<void> {
    let entry: StoredEntry;
    if (this.blobDir) {
      const fileName = `blob-${encodeURIComponent(storageKey)}`;
      const written = await Bun.write(this.blobPath(fileName), new Response(body instanceof ReadableStream ? body : (body as BodyInit)));
      entry = { contentType, objectId: `fake-${storageKey}`, size: written, fileName };
    } else {
      const bytes = await toUint8Array(body);
      entry = { contentType, objectId: `fake-${storageKey}`, size: bytes.byteLength, bytes };
    }
    this.entries.set(storageKey, entry);
    this.persistIndex();
    if (binding.storageAccountId != null) {
      await this.repository.upsertStorageObject({
        provider: this.provider,
        storageAccountId: binding.storageAccountId,
        storageKey,
        objectId: entry.objectId,
        contentType,
        size: entry.size,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString()
      });
    }
  }

  async get(_binding: StorageBinding, storageKey: string, range?: BlobRange | null): Promise<StoredBlob | null> {
    const entry = this.entries.get(storageKey);
    if (!entry) {
      return null;
    }
    this.downloadCounts.set(storageKey, (this.downloadCounts.get(storageKey) ?? 0) + 1);
    const total = entry.size;
    if (range && range.offset >= total) {
      throw new HttpError(416, "range_not_satisfiable", "Requested range is beyond the end of the stored blob.");
    }
    const offset = range?.offset ?? 0;
    const endInclusive = range ? Math.min(range.endInclusive ?? total - 1, total - 1) : total - 1;
    if (entry.fileName != null) {
      const slice = Bun.file(this.blobPath(entry.fileName)).slice(offset, endInclusive + 1);
      return {
        body: slice.stream(),
        contentType: entry.contentType,
        size: endInclusive - offset + 1,
        status: range ? 206 : 200,
        contentRange: range ? `bytes ${offset}-${endInclusive}/${total}` : null,
        arrayBuffer() {
          return slice.arrayBuffer();
        }
      };
    }
    const bytes = entry.bytes!.slice(offset, endInclusive + 1);
    return {
      body: new ReadableStream({
        start(controller) {
          controller.enqueue(bytes);
          controller.close();
        }
      }),
      contentType: entry.contentType,
      size: bytes.byteLength,
      status: range ? 206 : 200,
      contentRange: range ? `bytes ${offset}-${endInclusive}/${total}` : null,
      async arrayBuffer() {
        return bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
      }
    };
  }

  async delete(binding: StorageBinding, storageKey: string): Promise<void> {
    const entry = this.entries.get(storageKey);
    if (entry?.fileName != null) {
      rmSync(this.blobPath(entry.fileName), { force: true });
    }
    this.entries.delete(storageKey);
    this.persistIndex();
    if (binding.storageAccountId != null) {
      await this.repository.deleteStorageObject(this.provider, binding.storageAccountId, storageKey);
    }
  }

  async quota(): Promise<StorageQuota> {
    return {
      usedBytes: [...this.entries.values()].reduce((total, entry) => total + entry.size, 0),
      totalBytes: null
    };
  }

  // ----------------------------------------------------- ResumableUploadCapable

  async createResumableSession(
    binding: StorageBinding,
    storageKey: string,
    contentType: string,
    expectedSize: number
  ): Promise<string> {
    this.sessionCounter += 1;
    const sessionId = `s${this.sessionCounter}-${Math.random().toString(36).slice(2, 10)}`;
    const session: FakeResumableSession = {
      fileId: `fake-upload-${sessionId}`,
      storageKey,
      storageAccountId: binding.storageAccountId ?? null,
      contentType,
      expectedSize,
      received: 0,
      chunkPuts: 0,
      completed: false
    };
    if (this.blobDir) {
      session.tempFileName = `session-${sessionId}.part`;
      writeFileSync(this.blobPath(session.tempFileName), "");
    } else {
      session.parts = [];
    }
    this.sessions.set(sessionId, session);
    return `${this.publicBaseUrl}/__fake-drive/upload/${sessionId}`;
  }

  async probeResumableSession(_binding: StorageBinding, sessionUrl: string, _expectedSize: number): Promise<ResumableProbe> {
    const session = this.sessions.get(sessionUrl.slice(sessionUrl.lastIndexOf("/") + 1));
    if (!session) {
      return { status: "expired" };
    }
    if (session.completed) {
      return { status: "complete", fileId: session.fileId, size: session.received };
    }
    return { status: "incomplete", receivedUpTo: session.received };
  }

  async registerUploadedObject(
    binding: StorageBinding,
    storageKey: string,
    fileId: string,
    size: number,
    contentType: string
  ): Promise<void> {
    const session = [...this.sessions.values()].find((candidate) => candidate.fileId === fileId);
    if (!session || !session.completed) {
      throw new HttpError(500, "internal_error", `Fake Drive has no completed session for file ${fileId}.`);
    }
    let entry: StoredEntry;
    if (session.tempFileName != null) {
      const fileName = `blob-${encodeURIComponent(storageKey)}`;
      renameSync(this.blobPath(session.tempFileName), this.blobPath(fileName));
      entry = { contentType, objectId: fileId, size, fileName };
    } else {
      entry = { contentType, objectId: fileId, size, bytes: concatBytes(session.parts!) };
    }
    this.entries.set(storageKey, entry);
    this.persistIndex();
    if (binding.storageAccountId != null) {
      await this.repository.upsertStorageObject({
        provider: this.provider,
        storageAccountId: binding.storageAccountId,
        storageKey,
        objectId: fileId,
        contentType,
        size,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString()
      });
    }
  }

  async deleteObjectById(_binding: StorageBinding, fileId: string): Promise<void> {
    for (const [storageKey, entry] of this.entries) {
      if (entry.objectId === fileId) {
        if (entry.fileName != null) {
          rmSync(this.blobPath(entry.fileName), { force: true });
        }
        this.entries.delete(storageKey);
      }
    }
    this.persistIndex();
  }

  /**
   * The chunk-PUT endpoint behind the session URLs — the stand-in for Google's
   * upload host. The session URL is its own credential, so a leaked SharedWorld
   * bearer here is a client bug and fails the request loudly.
   */
  async handleUploadRequest(request: Request, url: URL): Promise<Response> {
    if (request.headers.get("authorization") != null) {
      return Response.json(
        { error: "bearer_leak", message: "Client sent an Authorization header to the fake Drive upload host." },
        { status: 401 }
      );
    }
    const session = this.sessions.get(url.pathname.slice(url.pathname.lastIndexOf("/") + 1));
    if (!session) {
      return Response.json({ error: "not_found", message: "Unknown or expired upload session." }, { status: 404 });
    }
    const contentRange = request.headers.get("content-range") ?? "";
    const statusProbe = /^bytes \*\/(\d+)$/.exec(contentRange);
    if (statusProbe) {
      return this.sessionStatusResponse(session);
    }
    const chunk = /^bytes (\d+)-(\d+)\/(\d+)$/.exec(contentRange);
    if (!chunk) {
      return Response.json({ error: "bad_request", message: `Unparseable Content-Range: ${contentRange}` }, { status: 400 });
    }
    const [start, endInclusive, total] = [Number(chunk[1]), Number(chunk[2]), Number(chunk[3])];
    if (total !== session.expectedSize) {
      return Response.json(
        { error: "bad_request", message: `Content-Range total ${total} does not match session size ${session.expectedSize}.` },
        { status: 400 }
      );
    }
    session.chunkPuts += 1;
    if (start > session.received) {
      // A gap: like real Drive, report the high-water mark so the client resyncs.
      return this.sessionStatusResponse(session);
    }
    const body = new Uint8Array(await request.arrayBuffer());
    if (body.byteLength !== endInclusive - start + 1) {
      return Response.json(
        { error: "bad_request", message: `Body length ${body.byteLength} does not match Content-Range ${contentRange}.` },
        { status: 400 }
      );
    }
    if (endInclusive + 1 > session.received) {
      const fresh = body.subarray(session.received - start);
      if (session.tempFileName != null) {
        appendFileSync(this.blobPath(session.tempFileName), fresh);
      } else {
        session.parts!.push(fresh.slice());
      }
      session.received = endInclusive + 1;
    }
    if (session.received === session.expectedSize) {
      session.completed = true;
    }
    return this.sessionStatusResponse(session);
  }

  private sessionStatusResponse(session: FakeResumableSession): Response {
    if (session.completed) {
      return Response.json({ id: session.fileId, size: String(session.received) }, { status: 200 });
    }
    const headers = new Headers();
    if (session.received > 0) {
      headers.set("range", `bytes=0-${session.received - 1}`);
    }
    return new Response(null, { status: 308, headers });
  }

  snapshot() {
    return {
      provider: this.provider,
      objects: [...this.entries.entries()]
        .map(([storageKey, entry]) => ({
          storageKey,
          contentType: entry.contentType,
          size: entry.size
        }))
        .sort((left, right) => left.storageKey.localeCompare(right.storageKey)),
      uploads: [...this.sessions.values()]
        .map((session) => ({
          storageKey: session.storageKey,
          expectedSize: session.expectedSize,
          received: session.received,
          chunkPuts: session.chunkPuts,
          completed: session.completed
        }))
        .sort((left, right) => left.storageKey.localeCompare(right.storageKey)),
      downloads: [...this.downloadCounts.entries()]
        .map(([storageKey, count]) => ({ storageKey, count }))
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

export interface IntegrationPersistence {
  /** File-backed sqlite for D1 so a harness restart is deploy-faithful. */
  dbPath?: string;
  /** Directory for per-world coordinator state snapshots. */
  realtimeStateDir?: string;
  /** Directory for fake-Drive blob bytes (multi-GB worlds must not live in memory). */
  blobDir?: string;
}

export function createIntegrationTestApp(publicBaseUrl: string, persistence: IntegrationPersistence = {}) {
  let state = createState(publicBaseUrl, persistence);

  return {
    reset() {
      // A reset means a fresh universe: wipe the persisted files too, or
      // stale coordinator state would resurrect into the new one.
      if (persistence.dbPath) {
        rmSync(persistence.dbPath, { force: true });
      }
      if (persistence.realtimeStateDir) {
        rmSync(persistence.realtimeStateDir, { recursive: true, force: true });
      }
      if (persistence.blobDir) {
        rmSync(persistence.blobDir, { recursive: true, force: true });
      }
      state = createState(publicBaseUrl, persistence);
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
      if (url.pathname.startsWith("/__fake-drive/upload/")) {
        return state.storageProvider.handleUploadRequest(request, url);
      }

      // Same post-response lane as the worker runtime (waitUntil): deferred
      // housekeeping runs detached, its failures logged, never awaited by
      // the response.
      return createRouter(state.service)(request, {
        waitUntil(task) {
          task.catch((error: unknown) => {
            console.warn("integration app: deferred task failed", String(error));
          });
        }
      });
    }
  };
}

function createState(publicBaseUrl: string, persistence: IntegrationPersistence = {}): IntegrationState {
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
  const repository = createSqliteRepository(persistence.dbPath ?? ":memory:");
  const storageProvider = new FakeGoogleDriveStorageProvider(repository, publicBaseUrl, persistence.blobDir ?? null);
  repository.attachManifestDocumentReader(providerManifestDocumentReader(storageProvider));
  // In-process realtime: real coordinator logic per world. The integration
  // server bridges realtime.onPublish to its WebSocket clients.
  const realtime = new LocalRealtimeService(repository, persistence.realtimeStateDir ?? null);
  return {
    env,
    storageProvider,
    realtime,
    service: new SharedWorldService(
      repository,
      new WorkerSignedUrlSigner(env),
      storageProvider,
      env,
      realtime
    )
  };
}

function concatBytes(parts: Uint8Array[]): Uint8Array {
  const total = parts.reduce((sum, part) => sum + part.byteLength, 0);
  const merged = new Uint8Array(total);
  let offset = 0;
  for (const part of parts) {
    merged.set(part, offset);
    offset += part.byteLength;
  }
  return merged;
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
