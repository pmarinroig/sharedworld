//! Migration runner. The worker's `migrations/0001…0029` are applied
//! verbatim (D1 is SQLite), followed by the box-only `0030+` files in this
//! crate. Applied names are tracked in `sw_migrations`.

use include_dir::{include_dir, Dir};

use crate::error::DbError;
use crate::pool::Db;

static WORKER_MIGRATIONS: Dir<'_> = include_dir!("$CARGO_MANIFEST_DIR/../../../backend/migrations");
static SERVER_MIGRATIONS: Dir<'_> = include_dir!("$CARGO_MANIFEST_DIR/migrations");

/// The worker's canonical schema (for the schema-diff test and tooling).
pub const WORKER_SCHEMA_SQL: &str = include_str!("../../../../backend/src/schema.sql");

#[derive(Debug, Clone)]
pub struct Migration {
    pub name: String,
    pub sql: String,
}

pub fn all_migrations() -> Vec<Migration> {
    let mut out: Vec<Migration> = Vec::new();
    for dir in [&WORKER_MIGRATIONS, &SERVER_MIGRATIONS] {
        let mut files: Vec<_> =
            dir.files().filter(|f| f.path().extension().is_some_and(|e| e == "sql")).collect();
        files.sort_by_key(|f| f.path().to_path_buf());
        for f in files {
            out.push(Migration {
                name: f.path().file_name().unwrap().to_string_lossy().into_owned(),
                sql: f.contents_utf8().expect("migration is utf8").to_string(),
            });
        }
    }
    out.sort_by(|a, b| a.name.cmp(&b.name));
    out
}

/// Apply pending migrations. Returns the names applied.
pub fn migrate(db: &Db) -> Result<Vec<String>, DbError> {
    let migrations = all_migrations();
    db.raw_blocking(move |conn| {
        conn.execute_batch(
            "CREATE TABLE IF NOT EXISTS sw_migrations (name TEXT PRIMARY KEY, applied_at TEXT NOT NULL)",
        )?;
        let applied: std::collections::HashSet<String> = conn
            .prepare("SELECT name FROM sw_migrations")?
            .query_map([], |r| r.get::<_, String>(0))?
            .collect::<Result<_, _>>()?;
        let mut done = Vec::new();
        for m in migrations {
            if applied.contains(&m.name) {
                continue;
            }
            let tx = conn.transaction()?;
            tx.execute_batch(&m.sql)?;
            tx.execute(
                "INSERT INTO sw_migrations (name, applied_at) VALUES (?, ?)",
                rusqlite::params![m.name, crate::time::now_iso()],
            )?;
            tx.commit()?;
            done.push(m.name);
        }
        Ok(done)
    })
}

/// Mark every known migration as applied without running it (used after
/// importing a `wrangler d1 export` dump, which already carries the schema).
pub fn mark_all_applied(db: &Db, up_to: Option<&str>) -> Result<usize, DbError> {
    let migrations = all_migrations();
    let up_to = up_to.map(|s| s.to_string());
    db.raw_blocking(move |conn| {
        conn.execute_batch(
            "CREATE TABLE IF NOT EXISTS sw_migrations (name TEXT PRIMARY KEY, applied_at TEXT NOT NULL)",
        )?;
        let mut n = 0;
        for m in migrations {
            if let Some(limit) = &up_to {
                if m.name.as_str() > limit.as_str() {
                    break;
                }
            }
            n += conn.execute(
                "INSERT OR IGNORE INTO sw_migrations (name, applied_at) VALUES (?, ?)",
                rusqlite::params![m.name, crate::time::now_iso()],
            )?;
        }
        Ok(n)
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use rusqlite::Connection;
    use std::collections::BTreeMap;

    #[derive(Debug, PartialEq, Eq)]
    struct TableShape {
        columns: Vec<(String, String, i64, Option<String>, i64)>,
        foreign_keys: Vec<(String, String, String)>,
    }

    #[derive(Debug, PartialEq, Eq)]
    struct SchemaShape {
        tables: BTreeMap<String, TableShape>,
        indexes: BTreeMap<String, (String, i64, Vec<String>)>,
    }

    fn describe(conn: &Connection) -> SchemaShape {
        let mut tables = BTreeMap::new();
        let names: Vec<String> = conn
            .prepare("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name <> 'sw_migrations' ORDER BY name")
            .unwrap()
            .query_map([], |r| r.get(0))
            .unwrap()
            .collect::<Result<_, _>>()
            .unwrap();
        for name in names {
            let mut columns: Vec<(String, String, i64, Option<String>, i64)> = conn
                .prepare(&format!("PRAGMA table_info(\"{name}\")"))
                .unwrap()
                .query_map([], |r| Ok((r.get(1)?, r.get(2)?, r.get(3)?, r.get(4)?, r.get(5)?)))
                .unwrap()
                .collect::<Result<_, _>>()
                .unwrap();
            columns.sort();
            let mut fks: Vec<(String, String, String)> = conn
                .prepare(&format!("PRAGMA foreign_key_list(\"{name}\")"))
                .unwrap()
                .query_map([], |r| Ok((r.get(2)?, r.get(3)?, r.get(4)?)))
                .unwrap()
                .collect::<Result<_, _>>()
                .unwrap();
            fks.sort();
            tables.insert(name, TableShape { columns, foreign_keys: fks });
        }
        let mut indexes = BTreeMap::new();
        let idx: Vec<(String, String)> = conn
            .prepare("SELECT name, tbl_name FROM sqlite_master WHERE type = 'index' AND sql IS NOT NULL ORDER BY name")
            .unwrap()
            .query_map([], |r| Ok((r.get(0)?, r.get(1)?)))
            .unwrap()
            .collect::<Result<_, _>>()
            .unwrap();
        for (name, tbl) in idx {
            let cols: Vec<String> = conn
                .prepare(&format!("PRAGMA index_info(\"{name}\")"))
                .unwrap()
                .query_map([], |r| r.get(2))
                .unwrap()
                .collect::<Result<_, _>>()
                .unwrap();
            let unique: i64 = conn
                .prepare(&format!("PRAGMA index_list(\"{tbl}\")"))
                .unwrap()
                .query_map([], |r| Ok((r.get::<_, String>(1)?, r.get::<_, i64>(2)?)))
                .unwrap()
                .filter_map(Result::ok)
                .find(|(n, _)| *n == name)
                .map(|(_, u)| u)
                .unwrap_or(0);
            indexes.insert(name, (tbl, unique, cols));
        }
        SchemaShape { tables, indexes }
    }

    #[test]
    fn worker_migrations_match_schema_sql() {
        let migrated = Connection::open_in_memory().unwrap();
        for m in all_migrations() {
            if m.name.as_str() >= "0030" {
                break;
            }
            migrated.execute_batch(&m.sql).unwrap_or_else(|e| panic!("{} failed: {e}", m.name));
        }
        let fresh = Connection::open_in_memory().unwrap();
        fresh.execute_batch(WORKER_SCHEMA_SQL).unwrap();
        assert_eq!(describe(&migrated), describe(&fresh));
    }

    #[test]
    fn runner_applies_everything_once() {
        let db = Db::open_memory().unwrap();
        let first = migrate(&db).unwrap();
        assert!(first.len() >= 29, "{first:?}");
        assert!(first.iter().any(|n| n.starts_with("0030")));
        let second = migrate(&db).unwrap();
        assert!(second.is_empty());
    }
}
