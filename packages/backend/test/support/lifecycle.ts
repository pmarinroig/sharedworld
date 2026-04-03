import { Database } from "bun:sqlite";
import { readFileSync } from "node:fs";

import type { CreateWorldRequest, HostAssignment, ReleaseHostRequest } from "../../../shared/src/index.ts";
import type { D1Database, D1PreparedStatement, D1ResultRow } from "../../src/env.ts";
import { D1SharedWorldRepository } from "../../src/d1-repository.ts";
import { MemorySharedWorldRepository } from "../../src/memory-repository.ts";
import type { RequestContext, SharedWorldRepository } from "../../src/repository.ts";
import type { AuthVerifier, BlobUrlSigner } from "../../src/service.ts";
import { createTestService, type LegacyCompatibleSharedWorldService } from "./service-fixtures.ts";

export const OWNER: RequestContext = { playerUuid: "player-owner", playerName: "Owner" };
export const GUEST: RequestContext = { playerUuid: "player-guest", playerName: "Guest" };
export const HOST_MEMBER: RequestContext = { playerUuid: "player-host", playerName: "Host" };

const authVerifier: AuthVerifier = {
  async verifyJoin() {
    return OWNER;
  }
};

export type ServiceFixture<TRepository extends SharedWorldRepository> = {
  label: string;
  repository: TRepository;
  service: LegacyCompatibleSharedWorldService;
  close(): void;
};

class NoopBlobSigner implements BlobUrlSigner {
  async signUpload(_worldId: string, storageKey: string, _requestOrigin?: string) {
    return { method: "PUT" as const, url: `https://example.invalid/upload/${storageKey}`, headers: {}, expiresAt: new Date().toISOString() };
  }

  async signDownload(_worldId: string, storageKey: string, _requestOrigin?: string) {
    return { method: "GET" as const, url: `https://example.invalid/download/${storageKey}`, headers: {}, expiresAt: new Date().toISOString() };
  }

  async deleteBlob() {
  }
}

class SqliteD1Database implements D1Database {
  constructor(private readonly db: Database) {}

  prepare(query: string): D1PreparedStatement {
    return new SqliteD1PreparedStatement(this.db, query);
  }
}

class SqliteD1PreparedStatement implements D1PreparedStatement {
  private values: SqliteBinding[] = [];

  constructor(
    private readonly db: Database,
    private readonly query: string
  ) {}

  bind(...values: unknown[]): D1PreparedStatement {
    this.values = values.map(asSqliteBinding);
    return this;
  }

  async first<T = D1ResultRow>(): Promise<T | null> {
    const row = this.db.query(this.query).get(...this.values) as T | null | undefined;
    return row ?? null;
  }

  async all<T = D1ResultRow>(): Promise<{ results: T[] }> {
    return {
      results: this.db.query(this.query).all(...this.values) as T[]
    };
  }

  async run(): Promise<{ success: boolean; meta?: Record<string, unknown> }> {
    this.db.query(this.query).run(...this.values);
    return { success: true };
  }
}

type SqliteBinding = string | number | bigint | boolean | Uint8Array | null;

function asSqliteBinding(value: unknown): SqliteBinding {
  if (value === undefined) {
    return null;
  }
  if (
    value == null
    || typeof value === "string"
    || typeof value === "number"
    || typeof value === "bigint"
    || typeof value === "boolean"
    || value instanceof Uint8Array
  ) {
    return value;
  }
  throw new Error(`Unsupported sqlite binding type: ${typeof value}`);
}

function createService(repository: SharedWorldRepository): LegacyCompatibleSharedWorldService {
  return createTestService(
    repository,
    authVerifier,
    new NoopBlobSigner(),
    {
      SESSION_TTL_HOURS: "24"
    }
  );
}

export function createMemoryFixture(): ServiceFixture<MemorySharedWorldRepository> {
  const repository = new MemorySharedWorldRepository();
  return {
    label: "memory",
    repository,
    service: createService(repository),
    close() {}
  };
}

export function createD1Fixture(): ServiceFixture<D1SharedWorldRepository> {
  const db = new Database(":memory:");
  db.exec("PRAGMA foreign_keys = ON;");
  db.exec(readFileSync(new URL("../../src/schema.sql", import.meta.url), "utf8"));
  const repository = new D1SharedWorldRepository(new SqliteD1Database(db));
  return {
    label: "d1",
    repository,
    service: createService(repository),
    close() {
      db.close(false);
    }
  };
}

export function lifecycleFixtures(): Array<ServiceFixture<SharedWorldRepository>> {
  return [createMemoryFixture(), createD1Fixture()];
}

export async function seedUsers(repository: SharedWorldRepository, ...players: RequestContext[]) {
  for (const player of players) {
    await repository.upsertUser({
      playerUuid: player.playerUuid,
      playerName: player.playerName,
      createdAt: "2099-01-01T00:00:00.000Z"
    });
  }
}

export async function addMember(repository: SharedWorldRepository, worldId: string, player: RequestContext, joinedAt: string) {
  await repository.addMembership({
    worldId,
    playerUuid: player.playerUuid,
    playerName: player.playerName,
    role: "member",
    joinedAt,
    deletedAt: null
  });
}

export async function createWorldAndReleaseSeedAssignment(
  fixture: ServiceFixture<SharedWorldRepository>,
  request: Partial<CreateWorldRequest> = {},
  now = new Date("2099-01-01T00:00:00.000Z")
) {
  const created = await fixture.service.createWorld(
    OWNER,
    {
      name: request.name ?? "World",
      motdLine1: request.motdLine1 ?? "MOTD",
      importSource: request.importSource ?? { type: "local-save", id: "save-1", name: "Save 1" },
      storageLinkSessionId: request.storageLinkSessionId ?? ""
    },
    now
  );
  await fixture.service.releaseHost(
    OWNER,
    created.world.id,
    {
      graceful: false,
      runtimeEpoch: created.initialUploadAssignment.runtimeEpoch,
      hostToken: created.initialUploadAssignment.hostToken
    },
    new Date(now.getTime() + 1_000)
  );
  return created;
}

export async function enterAndStartHosting(
  fixture: ServiceFixture<SharedWorldRepository>,
  ctx: RequestContext,
  worldId: string,
  joinTarget = "join.example",
  now = new Date("2099-01-02T00:00:00.000Z")
): Promise<HostAssignment> {
  const entered = await fixture.service.enterSession(ctx, worldId, {}, now);
  if (entered.action !== "host" || entered.assignment == null) {
    throw new Error(`Expected host assignment, got ${entered.action}`);
  }
  await fixture.service.heartbeatHost(
    ctx,
    worldId,
    {
      runtimeEpoch: entered.assignment.runtimeEpoch,
      hostToken: entered.assignment.hostToken,
      joinTarget
    },
    new Date(now.getTime() + 1_000)
  );
  return entered.assignment;
}

export async function releaseWithAssignment(
  fixture: ServiceFixture<SharedWorldRepository>,
  ctx: RequestContext,
  worldId: string,
  assignment: HostAssignment,
  request: Omit<ReleaseHostRequest, "runtimeEpoch" | "hostToken">,
  now: Date
) {
  return fixture.service.releaseHost(
    ctx,
    worldId,
    {
      ...request,
      runtimeEpoch: assignment.runtimeEpoch,
      hostToken: assignment.hostToken
    },
    now
  );
}
