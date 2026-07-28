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

    expect(Object.keys(migratedShape.tables)).toEqual(Object.keys(schemaShape.tables));
    for (const table of Object.keys(schemaShape.tables)) {
      expect(migratedShape.tables[table], `table ${table} diverges between migrations and schema.sql`).toEqual(
        schemaShape.tables[table]
      );
    }
    expect(migratedShape.indexes).toEqual(schemaShape.indexes);
  });
});
