import type {
  CreateWorldResult,
  EnterSessionResponse,
  HostAssignment,
  ObserveWaitingResponse,
  SessionToken,
  SnapshotManifest,
  StorageLinkSession,
  WorldDetails,
  WorldMembership,
  WorldRuntimeStatus,
  WorldSummary
} from "../../../shared/src/index.ts";

import { createRouter, type RouterService } from "../../src/router.ts";

class TestUrlPattern {
  private readonly pathname: string;

  constructor(init: { pathname: string }) {
    this.pathname = init.pathname;
  }

  exec(input: string) {
    const url = new URL(input);
    const actualSegments = url.pathname.split("/").filter(Boolean);
    const expectedSegments = this.pathname.split("/").filter(Boolean);
    const groups: Record<string, string> = {};
    for (let i = 0; i < expectedSegments.length; i += 1) {
      const expected = expectedSegments[i];
      if (expected == null) {
        return null;
      }
      if (expected.startsWith(":") && expected.endsWith("*")) {
        groups[expected.slice(1, -1)] = actualSegments.slice(i).join("/");
        return {
          pathname: {
            groups
          }
        };
      }
      const actual = actualSegments[i];
      if (actual == null) {
        return null;
      }
      if (expected.startsWith(":")) {
        groups[expected.slice(1)] = actual;
        continue;
      }
      if (expected !== actual) {
        return null;
      }
    }
    if (actualSegments.length !== expectedSegments.length) {
      return null;
    }
    return {
      pathname: {
        groups
      }
    };
  }
}

const globalScope = globalThis as typeof globalThis & { URLPattern?: typeof TestUrlPattern };
if (globalScope.URLPattern === undefined) {
  globalScope.URLPattern = TestUrlPattern;
}

const DEFAULT_SESSION: SessionToken = {
  token: "session-token",
  playerUuid: "player-owner",
  playerName: "Owner",
  expiresAt: "2099-01-01T00:00:00.000Z"
};

async function unexpectedRouteCall(name: keyof RouterService): Promise<never> {
  throw new Error(`Unexpected router call in test: ${name}`);
}

function membershipFixture(overrides: Partial<WorldMembership> = {}): WorldMembership {
  return {
    worldId: overrides.worldId ?? "world-1",
    playerUuid: overrides.playerUuid ?? "player-owner",
    playerName: overrides.playerName ?? "Owner",
    role: overrides.role ?? "owner",
    joinedAt: overrides.joinedAt ?? "2099-01-01T00:00:00.000Z",
    deletedAt: overrides.deletedAt ?? null
  };
}

export function worldSummaryFixture(overrides: Partial<WorldSummary> = {}): WorldSummary {
  return {
    id: overrides.id ?? "world-1",
    slug: overrides.slug ?? "world-1",
    name: overrides.name ?? "World 1",
    ownerUuid: overrides.ownerUuid ?? "player-owner",
    motd: overrides.motd ?? null,
    customIconStorageKey: overrides.customIconStorageKey ?? null,
    customIconDownload: overrides.customIconDownload ?? null,
    memberCount: overrides.memberCount ?? 1,
    status: overrides.status ?? "idle",
    lastSnapshotId: overrides.lastSnapshotId ?? null,
    lastSnapshotAt: overrides.lastSnapshotAt ?? null,
    activeHostUuid: overrides.activeHostUuid ?? null,
    activeHostPlayerName: overrides.activeHostPlayerName ?? null,
    activeJoinTarget: overrides.activeJoinTarget ?? null,
    onlinePlayerCount: overrides.onlinePlayerCount ?? 0,
    onlinePlayerNames: overrides.onlinePlayerNames ?? [],
    storageProvider: overrides.storageProvider ?? "google-drive",
    storageLinked: overrides.storageLinked ?? false,
    storageAccountEmail: overrides.storageAccountEmail ?? null
  };
}

export function worldDetailsFixture(overrides: Partial<WorldDetails> = {}): WorldDetails {
  const membership = overrides.membership ?? membershipFixture({ worldId: overrides.id });
  return {
    ...worldSummaryFixture(overrides),
    membership,
    memberships: overrides.memberships ?? [membership],
    storageUsage: overrides.storageUsage ?? null,
    activeInviteCode: overrides.activeInviteCode ?? null
  };
}

export function hostAssignmentFixture(overrides: Partial<HostAssignment> = {}): HostAssignment {
  return {
    worldId: overrides.worldId ?? "world-1",
    playerUuid: overrides.playerUuid ?? "player-owner",
    playerName: overrides.playerName ?? "Owner",
    runtimeEpoch: overrides.runtimeEpoch ?? 1,
    hostToken: overrides.hostToken ?? "host-token-1",
    startupDeadlineAt: overrides.startupDeadlineAt ?? null
  };
}

export function runtimeStatusFixture(overrides: Partial<WorldRuntimeStatus> = {}): WorldRuntimeStatus {
  return {
    worldId: overrides.worldId ?? "world-1",
    phase: overrides.phase ?? "idle",
    runtimeEpoch: overrides.runtimeEpoch ?? 0,
    hostUuid: overrides.hostUuid ?? null,
    hostPlayerName: overrides.hostPlayerName ?? null,
    candidateUuid: overrides.candidateUuid ?? null,
    candidatePlayerName: overrides.candidatePlayerName ?? null,
    joinTarget: overrides.joinTarget ?? null,
    startupDeadlineAt: overrides.startupDeadlineAt ?? null,
    runtimeTokenIssuedAt: overrides.runtimeTokenIssuedAt ?? null,
    lastProgressAt: overrides.lastProgressAt ?? null,
    updatedAt: overrides.updatedAt ?? "2099-01-01T00:00:00.000Z",
    revokedAt: overrides.revokedAt ?? null,
    startupProgress: overrides.startupProgress ?? null,
    uncleanShutdownWarning: overrides.uncleanShutdownWarning ?? null
  };
}

export function createWorldResultFixture(overrides: {
  world?: Partial<WorldDetails>;
  initialUploadAssignment?: Partial<HostAssignment>;
} = {}): CreateWorldResult {
  return {
    world: worldDetailsFixture(overrides.world),
    initialUploadAssignment: hostAssignmentFixture(overrides.initialUploadAssignment)
  };
}

export function enterSessionResponseFixture(overrides: {
  action?: EnterSessionResponse["action"];
  world?: Partial<WorldSummary>;
  latestManifest?: SnapshotManifest | null;
  runtime?: Partial<WorldRuntimeStatus>;
  assignment?: HostAssignment | null;
  waiterSessionId?: string | null;
} = {}): EnterSessionResponse {
  return {
    action: overrides.action ?? "wait",
    world: worldSummaryFixture(overrides.world),
    latestManifest: overrides.latestManifest ?? null,
    runtime: runtimeStatusFixture(overrides.runtime),
    assignment: overrides.assignment ?? null,
    waiterSessionId: overrides.waiterSessionId ?? null
  };
}

export function observeWaitingResponseFixture(overrides: {
  action?: ObserveWaitingResponse["action"];
  runtime?: Partial<WorldRuntimeStatus>;
  assignment?: HostAssignment | null;
  waiterSessionId?: string | null;
} = {}): ObserveWaitingResponse {
  return {
    action: overrides.action ?? "wait",
    runtime: runtimeStatusFixture(overrides.runtime),
    assignment: overrides.assignment ?? null,
    waiterSessionId: overrides.waiterSessionId ?? null
  };
}

export function storageLinkSessionFixture(overrides: Partial<StorageLinkSession> = {}): StorageLinkSession {
  return {
    id: overrides.id ?? "storage-link-1",
    provider: overrides.provider ?? "google-drive",
    status: overrides.status ?? "linked",
    authUrl: overrides.authUrl ?? "https://example.test/auth",
    expiresAt: overrides.expiresAt ?? "2099-01-01T00:00:00.000Z",
    linkedAccountEmail: overrides.linkedAccountEmail ?? "owner@example.com",
    accountDisplayName: overrides.accountDisplayName ?? "Owner",
    errorMessage: overrides.errorMessage ?? null
  };
}

const defaultRouterService = {
  async abandonFinalization(_ctx, _worldId, _request) {
    return unexpectedRouteCall("abandonFinalization");
  },
  async beginFinalization(_ctx, _worldId, _request) {
    return unexpectedRouteCall("beginFinalization");
  },
  async cancelWaiting(_ctx, _worldId, _request) {
    return unexpectedRouteCall("cancelWaiting");
  },
  async completeAuth(_request) {
    return unexpectedRouteCall("completeAuth");
  },
  async completeDevAuth(_request) {
    return unexpectedRouteCall("completeDevAuth");
  },
  async completeFinalization(_ctx, _worldId, _request) {
    return unexpectedRouteCall("completeFinalization");
  },
  async cancelStorageLink(_ctx, _sessionId) {
    return unexpectedRouteCall("cancelStorageLink");
  },
  async completeStorageLink(_sessionId, _request) {
    return unexpectedRouteCall("completeStorageLink");
  },
  async createChallenge() {
    return unexpectedRouteCall("createChallenge");
  },
  async createInvite(_ctx, _worldId) {
    return unexpectedRouteCall("createInvite");
  },
  async createStorageLink(_ctx, _request) {
    return unexpectedRouteCall("createStorageLink");
  },
  async createWorld(_ctx, _request) {
    return unexpectedRouteCall("createWorld");
  },
  async deleteSnapshot(_ctx, _worldId, _snapshotId) {
    return unexpectedRouteCall("deleteSnapshot");
  },
  async deleteWorld(_ctx, _worldId) {
    return unexpectedRouteCall("deleteWorld");
  },
  async downloadPlan(_ctx, _worldId, _requestOrFiles) {
    return unexpectedRouteCall("downloadPlan");
  },
  async downloadStorageBlob(_ctx, _worldId, _storageKey) {
    return unexpectedRouteCall("downloadStorageBlob");
  },
  async enterSession(_ctx, _worldId, _requestOrNow, _nowArg) {
    return unexpectedRouteCall("enterSession");
  },
  async finalizeSnapshot(_ctx, _worldId, _request, _now) {
    return unexpectedRouteCall("finalizeSnapshot");
  },
  async getSession(token) {
    return token === DEFAULT_SESSION.token ? DEFAULT_SESSION : null;
  },
  async getStorageLinkSession(_ctx, _sessionId) {
    return unexpectedRouteCall("getStorageLinkSession");
  },
  async getWorld(_ctx, _worldId) {
    return unexpectedRouteCall("getWorld");
  },
  async heartbeatHost(_ctx, _worldId, _request, _now) {
    return unexpectedRouteCall("heartbeatHost");
  },
  async kickMember(_ctx, _worldId, _removedPlayerUuid) {
    return unexpectedRouteCall("kickMember");
  },
  async latestManifest(_ctx, _worldId) {
    return unexpectedRouteCall("latestManifest");
  },
  async listSnapshots(_ctx, _worldId) {
    return unexpectedRouteCall("listSnapshots");
  },
  async listWorlds(_ctx) {
    return unexpectedRouteCall("listWorlds");
  },
  async observeWaiting(_ctx, _worldId, _request, _nowArg) {
    return unexpectedRouteCall("observeWaiting");
  },
  async prepareUploads(_ctx, _worldId, _request) {
    return unexpectedRouteCall("prepareUploads");
  },
  async redeemInvite(_ctx, _request, _now) {
    return unexpectedRouteCall("redeemInvite");
  },
  async refreshWaiting(_ctx, _worldId, _request, _nowArg) {
    return unexpectedRouteCall("refreshWaiting");
  },
  async releaseHost(_ctx, _worldId, _request, _now) {
    return unexpectedRouteCall("releaseHost");
  },
  async resetInvite(_ctx, _worldId) {
    return unexpectedRouteCall("resetInvite");
  },
  async restoreSnapshot(_ctx, _worldId, _snapshotId) {
    return unexpectedRouteCall("restoreSnapshot");
  },
  async runtimeStatus(_ctx, _worldId, _now) {
    return unexpectedRouteCall("runtimeStatus");
  },
  async setHostStartupProgress(_ctx, _worldId, _request, _now) {
    return unexpectedRouteCall("setHostStartupProgress");
  },
  async setPlayerPresence(_ctx, _worldId, _request, _now) {
    return unexpectedRouteCall("setPlayerPresence");
  },
  async updateWorld(_ctx, _worldId, _request) {
    return unexpectedRouteCall("updateWorld");
  },
  async uploadStorageBlob(_ctx, _worldId, _storageKey, _request) {
    return unexpectedRouteCall("uploadStorageBlob");
  }
} satisfies RouterService;

export function createRouterService(overrides: Partial<RouterService> = {}): RouterService {
  return {
    ...defaultRouterService,
    ...overrides
  };
}

export function lifecycleRouter(overrides: Partial<RouterService> = {}) {
  return createRouter(createRouterService(overrides));
}

export function authedRequest(path: string, method = "GET", body?: unknown) {
  return new Request(`http://127.0.0.1:8787${path}`, {
    method,
    headers: {
      authorization: `Bearer ${DEFAULT_SESSION.token}`,
      ...(body === undefined ? {} : { "content-type": "application/json" })
    },
    body: body === undefined ? undefined : JSON.stringify(body)
  });
}
