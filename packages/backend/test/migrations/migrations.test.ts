import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";

import { Database } from "bun:sqlite";
import { describe, expect, test } from "bun:test";

/**
 * Production D1 databases are built by applying migrations/ in order, while
 * every test in this suite loads src/schema.sql directly. Nothing else checks
 * that the two agree, or that each migration still applies on top of its true
 * predecessor state. This suite does both against in-memory SQLite.
 */

const BACKEND_ROOT = join(import.meta.dir, "../..");
const MIGRATIONS_DIR = join(BACKEND_ROOT, "migrations");

const migrationFiles = readdirSync(MIGRATIONS_DIR)
  .filter((name) => name.endsWith(".sql"))
  .sort();

function applyMigration(db: Database, fileName: string): void {
  db.exec(readFileSync(join(MIGRATIONS_DIR, fileName), "utf8"));
}

type TableShape = {
  columns: Array<{ name: string; type: string; notnull: number; dflt_value: unknown; pk: number }>;
  foreignKeys: Array<{ table: string; from: string; to: string }>;
};

type SchemaShape = {
  tables: Record<string, TableShape>;
  indexes: Record<string, { table: string; unique: number; columns: string[] }>;
};

function describeSchema(db: Database): SchemaShape {
  const tables: SchemaShape["tables"] = {};
  const tableRows = db
    .query<{ name: string }, []>("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
    .all();
  for (const { name } of tableRows) {
    tables[name] = {
      // Sorted by name: column position differs legitimately between a fresh
      // CREATE TABLE and an ALTER TABLE ADD COLUMN history, and D1 rows are
      // accessed by column name, never position.
      columns: db
        .query<TableShape["columns"][number], []>(`PRAGMA table_info(${JSON.stringify(name)})`)
        .all()
        .map(({ name: columnName, type, notnull, dflt_value, pk }) => ({ name: columnName, type, notnull, dflt_value, pk }))
        .sort((a, b) => a.name.localeCompare(b.name)),
      foreignKeys: db
        .query<{ table: string; from: string; to: string }, []>(`PRAGMA foreign_key_list(${JSON.stringify(name)})`)
        .all()
        .map(({ table, from, to }) => ({ table, from, to }))
        .sort((a, b) => `${a.from}`.localeCompare(`${b.from}`))
    };
  }

  const indexes: SchemaShape["indexes"] = {};
  const indexRows = db
    .query<{ name: string; tbl_name: string }, []>(
      "SELECT name, tbl_name FROM sqlite_master WHERE type = 'index' AND sql IS NOT NULL ORDER BY name"
    )
    .all();
  for (const { name, tbl_name } of indexRows) {
    const info = db.query<{ name: string }, []>(`PRAGMA index_info(${JSON.stringify(name)})`).all();
    const unique = db
      .query<{ name: string; unique: number }, []>(`PRAGMA index_list(${JSON.stringify(tbl_name)})`)
      .all()
      .find((index) => index.name === name)?.unique ?? 0;
    indexes[name] = { table: tbl_name, unique, columns: info.map((column) => column.name) };
  }

  return { tables, indexes };
}

/**
 * Documented divergence between the migration chain and schema.sql. Each entry
 * must keep existing exactly as recorded (a guard test enforces that); when
 * the drift is fixed by a new migration, delete the entry.
 */
type ColumnDrift = {
  table: string;
  column: string;
  migrated: Partial<TableShape["columns"][number]>;
  schema: Partial<TableShape["columns"][number]>;
};

const KNOWN_COLUMN_DRIFT: ColumnDrift[] = [
  {
    // KNOWN-DEFECT(schema-drift): schema.sql declares
    // handoff_waiters.waiter_session_id NOT NULL, but the column was added by
    // ALTER TABLE (which cannot add NOT NULL without a default), so migrated
    // production databases do not enforce it. Fresh dev/test databases do.
    table: "handoff_waiters",
    column: "waiter_session_id",
    migrated: { notnull: 0 },
    schema: { notnull: 1 }
  },
  // KNOWN-DEFECT(schema-drift): the ALTER TABLE migrations that added the
  // world_presence protocol columns had to supply DEFAULT values, which
  // schema.sql does not declare. Inserts always set these columns explicitly,
  // so the drift is benign but real.
  { table: "world_presence", column: "guest_session_epoch", migrated: { dflt_value: "0" }, schema: { dflt_value: null } },
  { table: "world_presence", column: "presence_sequence", migrated: { dflt_value: "0" }, schema: { dflt_value: null } },
  { table: "world_presence", column: "present", migrated: { dflt_value: "1" }, schema: { dflt_value: null } }
];

const KNOWN_FOREIGN_KEY_DRIFT = [
  {
    // KNOWN-DEFECT(schema-drift): schema.sql gives snapshot_packs a foreign
    // key snapshot_id -> snapshots.id, but the migration that created the
    // table omitted it, so migrated production databases do not enforce it.
    table: "snapshot_packs",
    missingInMigrated: { table: "snapshots", from: "snapshot_id", to: "id" }
  }
];

describe("migrations", () => {
  test("there are migrations to test", () => {
    expect(migrationFiles.length).toBeGreaterThanOrEqual(14);
  });

  test("each migration applies cleanly on top of its predecessors", () => {
    const db = new Database(":memory:");
    db.exec("PRAGMA foreign_keys = ON;");
    for (const fileName of migrationFiles) {
      try {
        applyMigration(db, fileName);
      } catch (error) {
        throw new Error(`Migration ${fileName} failed to apply on the state left by its predecessors: ${String(error)}`);
      }
    }
    db.close(false);
  });

  test("migrations applied in order produce the same schema as schema.sql", () => {
    const migrated = new Database(":memory:");
    for (const fileName of migrationFiles) {
      applyMigration(migrated, fileName);
    }

    const fromSchemaFile = new Database(":memory:");
    fromSchemaFile.exec(readFileSync(join(BACKEND_ROOT, "src/schema.sql"), "utf8"));

    const migratedShape = describeSchema(migrated);
    const schemaShape = describeSchema(fromSchemaFile);
    migrated.close(false);
    fromSchemaFile.close(false);

    for (const drift of KNOWN_FOREIGN_KEY_DRIFT) {
      const migratedKeys = migratedShape.tables[drift.table]?.foreignKeys ?? [];
      const schemaKeys = schemaShape.tables[drift.table]?.foreignKeys ?? [];
      const matches = (key: TableShape["foreignKeys"][number]) =>
        key.table === drift.missingInMigrated.table && key.from === drift.missingInMigrated.from && key.to === drift.missingInMigrated.to;
      expect(
        migratedKeys.some(matches),
        `known FK drift on ${drift.table} no longer exists on the migrations side — remove the entry`
      ).toBe(false);
      expect(
        schemaKeys.some(matches),
        `known FK drift on ${drift.table} changed on the schema.sql side — update or remove the entry`
      ).toBe(true);
      migratedKeys.push({ ...drift.missingInMigrated });
      migratedKeys.sort((a, b) => `${a.from}`.localeCompare(`${b.from}`));
    }

    for (const drift of KNOWN_COLUMN_DRIFT) {
      const migratedColumn = migratedShape.tables[drift.table]?.columns.find((column) => column.name === drift.column);
      const schemaColumn = schemaShape.tables[drift.table]?.columns.find((column) => column.name === drift.column);
      for (const [field, value] of Object.entries(drift.migrated)) {
        expect(
          migratedColumn?.[field as keyof typeof drift.migrated],
          `known drift on ${drift.table}.${drift.column} changed on the migrations side — update or remove the entry`
        ).toBe(value as never);
      }
      for (const [field, value] of Object.entries(drift.schema)) {
        expect(
          schemaColumn?.[field as keyof typeof drift.schema],
          `known drift on ${drift.table}.${drift.column} changed on the schema.sql side — update or remove the entry`
        ).toBe(value as never);
        // Verified as still present; neutralize it so the deep diff below
        // only reports NEW divergence.
        Object.assign(migratedColumn!, { [field]: value });
      }
    }

    expect(Object.keys(migratedShape.tables)).toEqual(Object.keys(schemaShape.tables));
    for (const table of Object.keys(schemaShape.tables)) {
      expect(migratedShape.tables[table], `table ${table} diverges between migrations and schema.sql`).toEqual(
        schemaShape.tables[table]
      );
    }
    expect(migratedShape.indexes).toEqual(schemaShape.indexes);
  });
});
