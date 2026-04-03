import { describe, expect, test } from "bun:test";

import type { D1Database, D1PreparedStatement, D1ResultRow } from "../../src/env.ts";
import { D1SharedWorldRepository } from "../../src/d1-repository.ts";
import type { WorldRuntimeRecord } from "../../src/runtime-protocol.ts";

type Row = Record<string, unknown>;

describe("D1SharedWorldRepository", () => {
  test("runtime protocol fields round-trip through getRuntimeRecord", async () => {
    const db = new FakeD1Database();
    const repository = new D1SharedWorldRepository(db);
    const runtime: WorldRuntimeRecord = {
      worldId: "world-1",
      phase: "host-starting",
      runtimeEpoch: 7,
      runtimeToken: "rt_token_7",
      hostUuid: "player-host",
      hostPlayerName: "Host",
      candidateUuid: null,
      joinTarget: null,
      claimedAt: "2099-01-03T00:00:00.000Z",
      expiresAt: "2099-01-03T00:05:00.000Z",
      startupDeadlineAt: "2099-01-03T00:01:30.000Z",
      runtimeTokenIssuedAt: "2099-01-03T00:00:00.000Z",
      lastProgressAt: "2099-01-03T00:00:10.000Z",
      updatedAt: "2099-01-03T00:00:10.000Z",
      revokedAt: null,
      startupProgress: {
        label: "Preparing world",
        mode: "indeterminate",
        fraction: null,
        updatedAt: "2099-01-03T00:00:10.000Z"
      }
    };

    await repository.upsertRuntimeRecord(runtime);

    const loaded = await repository.getRuntimeRecord("world-1", new Date("2099-01-03T00:00:20.000Z"));

    expect(loaded).not.toBeNull();
    expect(loaded).toEqual(runtime);
  });
});

class FakeD1Database implements D1Database {
  runtime: Row | null = null;

  prepare(query: string): D1PreparedStatement {
    return new FakeStatement(this, query);
  }
}

class FakeStatement implements D1PreparedStatement {
  private values: unknown[] = [];

  constructor(
    private readonly db: FakeD1Database,
    private readonly query: string
  ) {}

  bind(...values: unknown[]): D1PreparedStatement {
    this.values = values;
    return this;
  }

  async first<T = D1ResultRow>(): Promise<T | null> {
    if (this.query.includes("FROM world_runtime WHERE world_id = ?")) {
      const worldId = String(this.values[0]);
      if (!this.db.runtime || String(this.db.runtime.world_id) != worldId) {
        return null;
      }
      return projectSelectedColumns(this.query, this.db.runtime) as T;
    }
    throw new Error(`Unsupported first() query in fake D1 test DB: ${this.query}`);
  }

  async all<T = D1ResultRow>(): Promise<{ results: T[] }> {
    throw new Error(`Unsupported all() query in fake D1 test DB: ${this.query}`);
  }

  async run(): Promise<{ success: boolean; meta?: Record<string, unknown> }> {
    if (this.query.includes("INSERT INTO world_runtime")) {
      this.db.runtime = {
        world_id: this.values[0],
        host_uuid: this.values[1],
        host_player_name: this.values[2],
        runtime_phase: this.values[3],
        runtime_epoch: this.values[4],
        runtime_token: this.values[5],
        claimed_at: this.values[6],
        expires_at: this.values[7],
        join_target: this.values[8],
        candidate_uuid: this.values[9],
        revoked_at: this.values[10],
        startup_deadline_at: this.values[11],
        runtime_token_issued_at: this.values[12],
        last_progress_at: this.values[13],
        startup_progress_label: this.values[14],
        startup_progress_mode: this.values[15],
        startup_progress_fraction: this.values[16],
        startup_progress_updated_at: this.values[17],
        updated_at: this.values[18]
      };
      return { success: true };
    }
    if (this.query.includes("DELETE FROM world_runtime")) {
      const worldId = String(this.values[0]);
      if (this.db.runtime && String(this.db.runtime.world_id) === worldId) {
        this.db.runtime = null;
      }
      return { success: true };
    }
    throw new Error(`Unsupported run() query in fake D1 test DB: ${this.query}`);
  }
}

function projectSelectedColumns(query: string, row: Row): Row {
  const selectIndex = query.indexOf("SELECT");
  const fromIndex = query.indexOf("FROM");
  const selected = query
    .slice(selectIndex + "SELECT".length, fromIndex)
    .split(",")
    .map((column) => column.trim())
    .filter((column) => column.length > 0);
  const projected: Row = {};
  for (const column of selected) {
    projected[column] = row[column];
  }
  return projected;
}
