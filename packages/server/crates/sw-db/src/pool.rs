//! Connection pool: one dedicated writer thread (every write runs inside
//! `BEGIN IMMEDIATE … COMMIT`, so write serialization is a structural fact)
//! plus a pool of reader threads, each with its own connection. Every
//! statement goes through [`Conn`] and is instrumented (count, duration,
//! rows returned/changed, VM steps, full-scan steps) under a static name and
//! the current route (a tokio task-local set by the HTTP layer).

use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;
use std::time::Instant;

use crossbeam_channel::{unbounded, Sender};
use metrics::{counter, gauge, histogram};
use rusqlite::{Connection, OpenFlags, Params, Row, StatementStatus, TransactionBehavior};
use tokio::sync::oneshot;

use crate::error::DbError;

tokio::task_local! {
    /// Route label for DB accounting; set by the HTTP layer per request.
    pub static ROUTE: Arc<str>;
}

fn current_route() -> Option<Arc<str>> {
    ROUTE.try_with(|r| r.clone()).ok()
}

#[derive(Debug, Clone)]
pub struct DbOptions {
    pub path: Option<PathBuf>,
    /// Reader threads; 0 = reads run on the writer thread (tests, `:memory:`).
    pub readers: usize,
    pub busy_timeout_ms: u64,
}

impl DbOptions {
    pub fn file(path: impl Into<PathBuf>) -> Self {
        Self { path: Some(path.into()), readers: 4, busy_timeout_ms: 5_000 }
    }
    pub fn memory() -> Self {
        Self { path: None, readers: 0, busy_timeout_ms: 5_000 }
    }
}

type Job = Box<dyn FnOnce(&mut Connection) + Send + 'static>;

struct Inner {
    writer: Sender<Job>,
    readers: Option<Sender<Job>>,
    write_queue_depth: AtomicUsize,
    path: Option<PathBuf>,
}

/// Cheap to clone handle.
#[derive(Clone)]
pub struct Db(Arc<Inner>);

impl std::fmt::Debug for Db {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Db").field("path", &self.0.path).finish()
    }
}

fn open_connection(path: Option<&Path>, writer: bool, busy_timeout_ms: u64) -> rusqlite::Result<Connection> {
    let conn = match path {
        Some(p) => Connection::open_with_flags(
            p,
            OpenFlags::SQLITE_OPEN_READ_WRITE
                | OpenFlags::SQLITE_OPEN_CREATE
                | OpenFlags::SQLITE_OPEN_NO_MUTEX,
        )?,
        None => Connection::open_in_memory()?,
    };
    conn.busy_timeout(std::time::Duration::from_millis(busy_timeout_ms))?;
    conn.pragma_update(None, "foreign_keys", "ON")?;
    conn.pragma_update(None, "temp_store", "MEMORY")?;
    if path.is_some() {
        if writer {
            conn.pragma_update(None, "journal_mode", "WAL")?;
            conn.pragma_update(None, "synchronous", "NORMAL")?;
            conn.pragma_update(None, "wal_autocheckpoint", "2000")?;
        } else {
            conn.pragma_update(None, "query_only", "ON")?;
        }
        conn.pragma_update(None, "cache_size", "-32000")?;
        conn.pragma_update(None, "mmap_size", "268435456")?;
    }
    Ok(conn)
}

impl Db {
    pub fn open(options: DbOptions) -> Result<Db, DbError> {
        let path = options.path.clone();
        let busy = options.busy_timeout_ms;
        let writer_conn = open_connection(path.as_deref(), true, busy)?;
        let (wtx, wrx) = unbounded::<Job>();
        std::thread::Builder::new()
            .name("sw-db-writer".into())
            .spawn(move || {
                let mut conn = writer_conn;
                while let Ok(job) = wrx.recv() {
                    job(&mut conn);
                }
            })
            .map_err(|e| DbError::other(format!("spawn writer: {e}")))?;
        let readers = if options.readers > 0 && path.is_some() {
            let (rtx, rrx) = unbounded::<Job>();
            for i in 0..options.readers {
                let rrx = rrx.clone();
                let mut conn = open_connection(path.as_deref(), false, busy)?;
                std::thread::Builder::new()
                    .name(format!("sw-db-reader-{i}"))
                    .spawn(move || {
                        while let Ok(job) = rrx.recv() {
                            job(&mut conn);
                        }
                    })
                    .map_err(|e| DbError::other(format!("spawn reader: {e}")))?;
            }
            Some(rtx)
        } else {
            None
        };
        Ok(Db(Arc::new(Inner { writer: wtx, readers, write_queue_depth: AtomicUsize::new(0), path })))
    }

    pub fn open_memory() -> Result<Db, DbError> {
        Db::open(DbOptions::memory())
    }

    pub fn path(&self) -> Option<&Path> {
        self.0.path.as_deref()
    }

    /// Run `f` on a reader connection inside a deferred read transaction
    /// (consistent snapshot across the closure's statements).
    pub async fn read<T, F>(&self, f: F) -> Result<T, DbError>
    where
        T: Send + 'static,
        F: FnOnce(&Conn<'_>) -> Result<T, DbError> + Send + 'static,
    {
        let route = current_route();
        let (tx, rx) = oneshot::channel();
        let job: Job = Box::new(move |conn: &mut Connection| {
            let result = (|| {
                let txn = conn.transaction_with_behavior(TransactionBehavior::Deferred)?;
                let c = Conn { conn: &txn, route: route.clone() };
                let out = f(&c);
                txn.finish()?;
                out
            })();
            let _ = tx.send(result);
        });
        let target = self.0.readers.as_ref().unwrap_or(&self.0.writer);
        target.send(job).map_err(|_| DbError::Closed)?;
        rx.await.map_err(|_| DbError::Closed)?
    }

    /// Run `f` on the writer connection inside `BEGIN IMMEDIATE`; commits
    /// on `Ok`, rolls back on `Err`.
    pub async fn write<T, F>(&self, f: F) -> Result<T, DbError>
    where
        T: Send + 'static,
        F: FnOnce(&Conn<'_>) -> Result<T, DbError> + Send + 'static,
    {
        let route = current_route();
        let (tx, rx) = oneshot::channel();
        let inner = self.0.clone();
        inner.write_queue_depth.fetch_add(1, Ordering::Relaxed);
        let queued_at = Instant::now();
        let job: Job = Box::new(move |conn: &mut Connection| {
            let depth = inner.write_queue_depth.fetch_sub(1, Ordering::Relaxed) - 1;
            gauge!("db_write_queue_depth").set(depth as f64);
            histogram!("db_write_queue_wait_seconds").record(queued_at.elapsed().as_secs_f64());
            let started = Instant::now();
            let result = (|| {
                let txn = conn.transaction_with_behavior(TransactionBehavior::Immediate)?;
                let c = Conn { conn: &txn, route: route.clone() };
                match f(&c) {
                    Ok(v) => {
                        txn.commit()?;
                        Ok(v)
                    }
                    Err(e) => {
                        drop(txn);
                        Err(e)
                    }
                }
            })();
            histogram!("db_txn_duration_seconds").record(started.elapsed().as_secs_f64());
            let _ = tx.send(result);
        });
        self.0.writer.send(job).map_err(|_| DbError::Closed)?;
        rx.await.map_err(|_| DbError::Closed)?
    }

    /// Blocking variant for tools/tests (runs on the writer thread).
    pub fn write_blocking<T, F>(&self, f: F) -> Result<T, DbError>
    where
        T: Send + 'static,
        F: FnOnce(&Conn<'_>) -> Result<T, DbError> + Send + 'static,
    {
        let (tx, rx) = std::sync::mpsc::channel();
        let job: Job = Box::new(move |conn: &mut Connection| {
            let result = (|| {
                let txn = conn.transaction_with_behavior(TransactionBehavior::Immediate)?;
                let c = Conn { conn: &txn, route: None };
                match f(&c) {
                    Ok(v) => {
                        txn.commit()?;
                        Ok(v)
                    }
                    Err(e) => {
                        drop(txn);
                        Err(e)
                    }
                }
            })();
            let _ = tx.send(result);
        });
        self.0.writer.send(job).map_err(|_| DbError::Closed)?;
        rx.recv().map_err(|_| DbError::Closed)?
    }

    /// Blocking read (tools/tests).
    pub fn read_blocking<T, F>(&self, f: F) -> Result<T, DbError>
    where
        T: Send + 'static,
        F: FnOnce(&Conn<'_>) -> Result<T, DbError> + Send + 'static,
    {
        let (tx, rx) = std::sync::mpsc::channel();
        let job: Job = Box::new(move |conn: &mut Connection| {
            let c = Conn { conn, route: None };
            let _ = tx.send(f(&c));
        });
        let target = self.0.readers.as_ref().unwrap_or(&self.0.writer);
        target.send(job).map_err(|_| DbError::Closed)?;
        rx.recv().map_err(|_| DbError::Closed)?
    }

    /// Raw access on the writer thread without an implicit transaction
    /// (migrations, PRAGMAs, `VACUUM`).
    pub fn raw_blocking<T, F>(&self, f: F) -> Result<T, DbError>
    where
        T: Send + 'static,
        F: FnOnce(&mut Connection) -> Result<T, DbError> + Send + 'static,
    {
        let (tx, rx) = std::sync::mpsc::channel();
        let job: Job = Box::new(move |conn: &mut Connection| {
            let _ = tx.send(f(conn));
        });
        self.0.writer.send(job).map_err(|_| DbError::Closed)?;
        rx.recv().map_err(|_| DbError::Closed)?
    }
}

/// An instrumented connection handle passed to read/write closures.
pub struct Conn<'a> {
    conn: &'a Connection,
    route: Option<Arc<str>>,
}

impl<'a> Conn<'a> {
    pub fn raw(&self) -> &Connection {
        self.conn
    }

    fn record(
        &self,
        name: &'static str,
        kind: &'static str,
        started: Instant,
        rows: usize,
        changed: usize,
        vm: i32,
        scan: i32,
    ) {
        counter!("db_stmt_total", "name" => name, "kind" => kind).increment(1);
        histogram!("db_stmt_duration_seconds", "name" => name).record(started.elapsed().as_secs_f64());
        if rows > 0 {
            counter!("db_rows_returned_total", "name" => name).increment(rows as u64);
        }
        if changed > 0 {
            counter!("db_rows_changed_total", "name" => name).increment(changed as u64);
        }
        if vm > 0 {
            counter!("db_vm_steps_total", "name" => name).increment(vm as u64);
        }
        if scan > 0 {
            counter!("db_fullscan_steps_total", "name" => name).increment(scan as u64);
        }
        if let Some(route) = &self.route {
            let route = route.to_string();
            if rows > 0 {
                counter!("db_route_rows_returned_total", "route" => route.clone()).increment(rows as u64);
            }
            if changed > 0 {
                counter!("db_route_rows_changed_total", "route" => route.clone()).increment(changed as u64);
            }
            counter!("db_route_stmt_total", "route" => route).increment(1);
        }
    }

    /// Query all rows, mapping each with `map`.
    pub fn query<T, P, F>(
        &self,
        name: &'static str,
        sql: &str,
        params: P,
        mut map: F,
    ) -> Result<Vec<T>, DbError>
    where
        P: Params,
        F: FnMut(&Row<'_>) -> rusqlite::Result<T>,
    {
        let started = Instant::now();
        let mut stmt = self.conn.prepare_cached(sql)?;
        let mut rows = stmt.query(params)?;
        let mut out = Vec::new();
        while let Some(row) = rows.next()? {
            out.push(map(row)?);
        }
        drop(rows);
        let (vm, scan) = take_steps(&stmt);
        self.record(name, "read", started, out.len(), 0, vm, scan);
        Ok(out)
    }

    /// First row or `None`.
    pub fn query_one<T, P, F>(
        &self,
        name: &'static str,
        sql: &str,
        params: P,
        map: F,
    ) -> Result<Option<T>, DbError>
    where
        P: Params,
        F: FnOnce(&Row<'_>) -> rusqlite::Result<T>,
    {
        let started = Instant::now();
        let mut stmt = self.conn.prepare_cached(sql)?;
        let mut rows = stmt.query(params)?;
        let out = match rows.next()? {
            Some(row) => Some(map(row)?),
            None => None,
        };
        drop(rows);
        let (vm, scan) = take_steps(&stmt);
        self.record(name, "read", started, usize::from(out.is_some()), 0, vm, scan);
        Ok(out)
    }

    /// Execute a statement; returns `changes()`.
    pub fn execute<P: Params>(&self, name: &'static str, sql: &str, params: P) -> Result<usize, DbError> {
        let started = Instant::now();
        let mut stmt = self.conn.prepare_cached(sql)?;
        let changed = stmt.execute(params)?;
        let (vm, scan) = take_steps(&stmt);
        self.record(name, "write", started, 0, changed, vm, scan);
        Ok(changed)
    }

    /// Execute a multi-statement SQL script (migrations).
    pub fn execute_batch(&self, name: &'static str, sql: &str) -> Result<(), DbError> {
        let started = Instant::now();
        self.conn.execute_batch(sql)?;
        self.record(name, "batch", started, 0, 0, 0, 0);
        Ok(())
    }
}

fn take_steps(stmt: &rusqlite::Statement<'_>) -> (i32, i32) {
    let vm = stmt.get_status(StatementStatus::VmStep);
    let scan = stmt.get_status(StatementStatus::FullscanStep);
    stmt.reset_status(StatementStatus::VmStep);
    stmt.reset_status(StatementStatus::FullscanStep);
    (vm, scan)
}

/// Convenience for the common nullable TEXT column read.
pub fn opt_string(row: &Row<'_>, idx: &str) -> rusqlite::Result<Option<String>> {
    row.get::<_, Option<String>>(idx)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn write_then_read_roundtrip() {
        let db = Db::open_memory().unwrap();
        db.write(|c| {
            c.execute_batch("init", "CREATE TABLE t (id INTEGER PRIMARY KEY, v TEXT)")?;
            c.execute("ins", "INSERT INTO t (v) VALUES (?)", ["a"])?;
            Ok(())
        })
        .await
        .unwrap();
        let rows =
            db.read(|c| c.query("sel", "SELECT v FROM t", [], |r| r.get::<_, String>(0))).await.unwrap();
        assert_eq!(rows, vec!["a".to_string()]);
        // A failing write rolls back.
        let err = db
            .write(|c| {
                c.execute("ins", "INSERT INTO t (v) VALUES (?)", ["b"])?;
                Err::<(), _>(DbError::other("boom"))
            })
            .await;
        assert!(err.is_err());
        let n = db
            .read(|c| c.query_one("cnt", "SELECT COUNT(*) FROM t", [], |r| r.get::<_, i64>(0)))
            .await
            .unwrap();
        assert_eq!(n, Some(1));
    }

    #[tokio::test]
    async fn file_db_with_readers() {
        let dir = tempfile::tempdir().unwrap();
        let db = Db::open(DbOptions::file(dir.path().join("t.db"))).unwrap();
        db.write(|c| c.execute_batch("init", "CREATE TABLE t (id INTEGER PRIMARY KEY)")).await.unwrap();
        db.write(|c| c.execute("ins", "INSERT INTO t DEFAULT VALUES", []).map(|_| ())).await.unwrap();
        let n = db
            .read(|c| c.query_one("cnt", "SELECT COUNT(*) FROM t", [], |r| r.get::<_, i64>(0)))
            .await
            .unwrap();
        assert_eq!(n, Some(1));
        let mode: String = db
            .read_blocking(|c| Ok(c.raw().pragma_query_value(None, "journal_mode", |r| r.get(0))?))
            .unwrap();
        assert_eq!(mode, "wal");
    }
}
