import { Database } from "bun:sqlite";
import { describe, expect, test } from "bun:test";

import { SqlCoordinatorStore } from "../../src/realtime/do.ts";

/**
 * The DO-SQLite kv store mirrors the table in memory: one load per object
 * wake, zero SELECTs per read afterwards, writes and deletes write-through.
 * Rows read on DO SQLite are billed and capped, and each coordinator call
 * touched the same dozen keys ~20 times before this mirror existed.
 */
function fakeSqlStorage() {
  const db = new Database(":memory:");
  const counts = { select: 0, write: 0 };
  return {
    counts,
    db,
    sql: {
      exec(query: string, ...bindings: unknown[]) {
        if (/^\s*SELECT/i.test(query)) {
          counts.select += 1;
        } else if (/^\s*(INSERT|DELETE|UPDATE)/i.test(query)) {
          counts.write += 1;
        }
        const rows = db.query(query).all(...(bindings as never[])) as Record<string, unknown>[];
        return { toArray: () => rows };
      }
    }
  };
}

describe("SqlCoordinatorStore mirror", () => {
  test("loads once and serves every read from memory", () => {
    const { sql, counts } = fakeSqlStorage();
    const store = new SqlCoordinatorStore(sql);
    expect(counts.select).toBe(1);

    for (let index = 0; index < 20; index += 1) {
      store.getRuntime();
      store.listWaiters();
      store.getHostLink();
      store.getStatusFingerprint();
    }
    expect(counts.select).toBe(1);
    expect(store.getRuntime()).toBeNull();
    expect(store.listWaiters()).toEqual([]);
  });

  test("writes and deletes go through to SQLite and a fresh store sees them", () => {
    const { sql, db, counts } = fakeSqlStorage();
    const store = new SqlCoordinatorStore(sql);
    store.setStatusFingerprint("fp-1");
    store.upsertWaiter({ playerUuid: "p1", playerName: "One" } as never);
    store.upsertWaiter({ playerUuid: "p2", playerName: "Two" } as never);
    store.deleteWaiter("p1");
    expect(counts.write).toBe(4);
    expect(counts.select).toBe(1);

    expect(store.getStatusFingerprint()).toBe("fp-1");
    expect(store.listWaiters().map((waiter) => waiter.playerUuid)).toEqual(["p2"]);
    expect(db.query("SELECT COUNT(*) AS n FROM kv").get()).toEqual({ n: 2 });

    const reopened = new SqlCoordinatorStore(sql);
    expect(reopened.getStatusFingerprint()).toBe("fp-1");
    expect(reopened.listWaiters().map((waiter) => waiter.playerUuid)).toEqual(["p2"]);

    store.clearAll();
    expect(store.getStatusFingerprint()).toBeNull();
    expect(db.query("SELECT COUNT(*) AS n FROM kv").get()).toEqual({ n: 0 });
  });

  test("callers never share a cached object", () => {
    const { sql } = fakeSqlStorage();
    const store = new SqlCoordinatorStore(sql);
    store.setHostLink({ connected: true, graceDeadlineAt: null });
    const first = store.getHostLink();
    first.connected = false;
    expect(store.getHostLink().connected).toBe(true);
  });
});
