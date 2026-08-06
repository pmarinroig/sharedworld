import { Database } from "bun:sqlite";
import { readFileSync } from "node:fs";

import type { D1Database, D1PreparedStatement, D1ResultRow } from "../../src/env.ts";
import { D1SharedWorldRepository } from "../../src/d1-repository.ts";

/**
 * Tests run the production D1SharedWorldRepository against an in-memory SQLite
 * database loaded with the real schema. There is intentionally no separate
 * in-memory repository implementation: what the tests exercise is exactly what
 * production runs.
 */
export function createSqliteRepository(): D1SharedWorldRepository & { close(): void; raw: Database } {
  const db = new Database(":memory:");
  db.exec("PRAGMA foreign_keys = ON;");
  db.exec(readFileSync(new URL("../../src/schema.sql", import.meta.url), "utf8"));
  seedKnownTestPlayers(db);
  const repository = new D1SharedWorldRepository(new SqliteD1Database(db)) as D1SharedWorldRepository & { close(): void; raw: Database };
  repository.close = () => db.close(false);
  // Raw handle so tests can assert physical row counts (e.g. that inherited
  // snapshot packs really wrote zero member rows), not just wire-level shapes.
  repository.raw = db;
  return repository;
}

/**
 * Production code never references a player before auth has created their user
 * row, and the schema enforces that with foreign keys. Tests construct request
 * contexts directly, so the factory pre-seeds every player identity the suite
 * uses. A foreign-key failure in a test means its player is missing here.
 */
const KNOWN_TEST_PLAYERS: Array<[string, string]> = [
  ["player-owner", "Owner"],
  ["player-guest", "Guest"],
  ["player-guest-1", "Guest One"],
  ["player-guest-2", "Guest Two"],
  ["player-host", "Host"],
  ["player-member", "Member"],
  ["player-outsider", "Outsider"],
  ["player-a", "Alpha"],
  ["player-alpha", "Alpha"],
  ["player-b", "Bravo"],
  ["player-bravo", "Bravo"],
  ["22222222222222222222222222222222", "DevTwo"],
  ["33333333333333333333333333333333", "DevThree"]
];

function seedKnownTestPlayers(db: Database): void {
  const insert = db.query("INSERT INTO users (player_uuid, player_name, created_at) VALUES (?, ?, ?)");
  for (const [playerUuid, playerName] of KNOWN_TEST_PLAYERS) {
    insert.run(playerUuid, playerName, "2000-01-01T00:00:00.000Z");
  }
}

export class SqliteD1Database implements D1Database {
  constructor(private readonly db: Database) {}

  prepare(query: string): D1PreparedStatement {
    return new SqliteD1PreparedStatement(this.db, query);
  }

  /** Mirrors D1's transactional batch: all statements land or none do. */
  async batch(statements: D1PreparedStatement[]): Promise<Array<{ success: boolean; meta?: Record<string, unknown> }>> {
    const results: Array<{ success: boolean; meta?: Record<string, unknown> }> = [];
    this.db.exec("BEGIN");
    try {
      for (const statement of statements) {
        results.push(await statement.run());
      }
      this.db.exec("COMMIT");
    } catch (error) {
      this.db.exec("ROLLBACK");
      throw error;
    }
    return results;
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
    const result = this.db.query(this.query).run(...this.values);
    return { success: true, meta: { changes: Number(result.changes) } };
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
