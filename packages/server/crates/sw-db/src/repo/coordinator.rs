//! 0030 coordinator state: per-world kv rows + alarm (box equivalent of DO
//! storage). Written only by the coordinator actor.

use rusqlite::params;

use super::Repository;
use crate::error::DbError;

impl Repository {
    pub async fn coordinator_kv_load(&self, world_id: &str) -> Result<Vec<(String, String)>, DbError> {
        let w = world_id.to_string();
        self.db
            .read(move |c| {
                c.query(
                    "coordinator_kv.load",
                    "SELECT key, value FROM coordinator_kv WHERE world_id = ?",
                    params![w],
                    |r| Ok((r.get(0)?, r.get(1)?)),
                )
            })
            .await
    }

    /// Apply one call's dirty set atomically: optional clear-all, then
    /// upserts/deletes, then the alarm row (`Some(Some(at))` set, `Some(None)`
    /// clear, `None` untouched).
    pub async fn coordinator_flush(
        &self,
        world_id: &str,
        cleared: bool,
        dirty: Vec<(String, Option<String>)>,
        alarm: Option<Option<String>>,
    ) -> Result<(), DbError> {
        if !cleared && dirty.is_empty() && alarm.is_none() {
            return Ok(());
        }
        let w = world_id.to_string();
        self.db
            .write(move |c| {
                if cleared {
                    c.execute("coordinator_kv.clear", "DELETE FROM coordinator_kv WHERE world_id = ?", params![w])?;
                }
                for (k, v) in dirty {
                    match v {
                        Some(v) => {
                            c.execute(
                                "coordinator_kv.upsert",
                                "INSERT INTO coordinator_kv (world_id, key, value) VALUES (?, ?, ?)
                                 ON CONFLICT(world_id, key) DO UPDATE SET value = excluded.value",
                                params![w, k, v],
                            )?;
                        }
                        None => {
                            c.execute("coordinator_kv.delete", "DELETE FROM coordinator_kv WHERE world_id = ? AND key = ?", params![w, k])?;
                        }
                    }
                }
                match alarm {
                    Some(Some(at)) => {
                        c.execute(
                            "coordinator_alarms.set",
                            "INSERT INTO coordinator_alarms (world_id, alarm_at) VALUES (?, ?) ON CONFLICT(world_id) DO UPDATE SET alarm_at = excluded.alarm_at",
                            params![w, at],
                        )?;
                    }
                    Some(None) => {
                        c.execute("coordinator_alarms.clear", "DELETE FROM coordinator_alarms WHERE world_id = ?", params![w])?;
                    }
                    None => {}
                }
                Ok(())
            })
            .await
    }

    pub async fn coordinator_alarms_all(&self) -> Result<Vec<(String, String)>, DbError> {
        self.db
            .read(|c| {
                c.query(
                    "coordinator_alarms.all",
                    "SELECT world_id, alarm_at FROM coordinator_alarms",
                    [],
                    |r| Ok((r.get(0)?, r.get(1)?)),
                )
            })
            .await
    }

    /// Cutover import: replace a world's kv rows wholesale.
    pub async fn coordinator_kv_replace(
        &self,
        world_id: &str,
        rows: Vec<(String, String)>,
    ) -> Result<(), DbError> {
        let w = world_id.to_string();
        self.db
            .write(move |c| {
                c.execute(
                    "coordinator_kv.clear",
                    "DELETE FROM coordinator_kv WHERE world_id = ?",
                    params![w],
                )?;
                for (k, v) in rows {
                    c.execute(
                        "coordinator_kv.insert",
                        "INSERT INTO coordinator_kv (world_id, key, value) VALUES (?, ?, ?)",
                        params![w, k, v],
                    )?;
                }
                Ok(())
            })
            .await
    }
}
