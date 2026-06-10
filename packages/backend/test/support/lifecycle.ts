import type { CreateWorldRequest, HostAssignment, ReleaseHostRequest } from "../../../shared/src/index.ts";
import type { RequestContext, SharedWorldRepository } from "../../src/repository.ts";
import type { AuthVerifier, BlobUrlSigner } from "../../src/service.ts";
import { createSqliteRepository } from "./sqlite-d1.ts";
import { createTestService, type TestDriverSharedWorldService } from "./service-fixtures.ts";

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
  service: TestDriverSharedWorldService;
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

function createService(repository: SharedWorldRepository): TestDriverSharedWorldService {
  return createTestService(
    repository,
    authVerifier,
    new NoopBlobSigner(),
    {
      SESSION_TTL_HOURS: "24"
    }
  );
}

export function createD1Fixture(): ServiceFixture<SharedWorldRepository> {
  const repository = createSqliteRepository();
  return {
    label: "d1",
    repository,
    service: createService(repository),
    close() {
      repository.close();
    }
  };
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
