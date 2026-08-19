//! `SessionRepository`: auth challenges, Mojang key cache, users, sessions.

use rusqlite::params;
use sw_contracts::SessionToken;

use super::records::*;
use super::Repository;
use crate::error::DbError;
use crate::time;

impl Repository {
    pub async fn create_challenge(&self, challenge: AuthChallengeRecord) -> Result<(), DbError> {
        // Piggybacked bounded sweep: challenges are 5-minute one-shots.
        let sweep_before = time::to_iso(time::now() - chrono::Duration::minutes(60));
        self.db
            .write(move |c| {
                c.execute(
                    "auth_challenges.sweep",
                    "DELETE FROM auth_challenges WHERE nonce IN (
                       SELECT nonce FROM auth_challenges WHERE expires_at < ? LIMIT 25
                     )",
                    params![sweep_before],
                )?;
                c.execute(
                    "auth_challenges.insert",
                    "INSERT INTO auth_challenges (nonce, expires_at, used_at) VALUES (?, ?, ?)",
                    params![challenge.server_id, challenge.expires_at, challenge.used_at],
                )?;
                Ok(())
            })
            .await
    }

    pub async fn get_challenge(&self, server_id: &str) -> Result<Option<AuthChallengeRecord>, DbError> {
        let server_id = server_id.to_string();
        self.db
            .read(move |c| {
                c.query_one(
                    "auth_challenges.get",
                    "SELECT nonce, expires_at, used_at FROM auth_challenges WHERE nonce = ?",
                    params![server_id],
                    |r| {
                        Ok(AuthChallengeRecord {
                            server_id: r.get("nonce")?,
                            expires_at: r.get("expires_at")?,
                            used_at: r.get("used_at")?,
                        })
                    },
                )
            })
            .await
    }

    pub async fn mark_challenge_used(&self, server_id: &str, used_at: &str) -> Result<(), DbError> {
        let (server_id, used_at) = (server_id.to_string(), used_at.to_string());
        self.db
            .write(move |c| {
                c.execute(
                    "auth_challenges.mark_used",
                    "UPDATE auth_challenges SET used_at = ? WHERE nonce = ?",
                    params![used_at, server_id],
                )?;
                Ok(())
            })
            .await
    }

    pub async fn get_mojang_services_keys(&self) -> Result<Option<MojangServicesKeysRow>, DbError> {
        self.db
            .read(|c| {
                c.query_one(
                    "mojang_keys.get",
                    "SELECT fetched_at, keys_json FROM mojang_services_keys WHERE id = 1",
                    [],
                    |r| {
                        Ok(MojangServicesKeysRow {
                            fetched_at: r.get("fetched_at")?,
                            keys_json: r.get("keys_json")?,
                        })
                    },
                )
            })
            .await
    }

    pub async fn put_mojang_services_keys(&self, fetched_at: &str, keys_json: &str) -> Result<(), DbError> {
        let (fetched_at, keys_json) = (fetched_at.to_string(), keys_json.to_string());
        self.db
            .write(move |c| {
                c.execute(
                    "mojang_keys.put",
                    "INSERT INTO mojang_services_keys (id, fetched_at, keys_json)
                     VALUES (1, ?, ?)
                     ON CONFLICT(id) DO UPDATE SET fetched_at = excluded.fetched_at, keys_json = excluded.keys_json",
                    params![fetched_at, keys_json],
                )?;
                Ok(())
            })
            .await
    }

    pub async fn upsert_user(&self, user: UserRecord) -> Result<(), DbError> {
        self.db
            .write(move |c| {
                // Conditional update: a same-name login must not count as a row write.
                c.execute(
                    "users.upsert",
                    "INSERT INTO users (player_uuid, player_name, created_at)
                     VALUES (?, ?, ?)
                     ON CONFLICT(player_uuid) DO UPDATE SET player_name = excluded.player_name
                     WHERE excluded.player_name <> users.player_name",
                    params![user.player_uuid, user.player_name, user.created_at],
                )?;
                Ok(())
            })
            .await
    }

    pub async fn create_session(&self, session: SessionToken) -> Result<(), DbError> {
        let sweep_before = time::to_iso(time::now() - chrono::Duration::hours(24));
        let created_at = time::now_iso();
        self.db
            .write(move |c| {
                c.execute(
                    "user_sessions.sweep",
                    "DELETE FROM user_sessions WHERE token IN (
                       SELECT token FROM user_sessions WHERE expires_at < ? LIMIT 25
                     )",
                    params![sweep_before],
                )?;
                c.execute(
                    "user_sessions.insert",
                    "INSERT INTO user_sessions (token, player_uuid, player_name, created_at, expires_at) VALUES (?, ?, ?, ?, ?)",
                    params![session.token, session.player_uuid, session.player_name, created_at, session.expires_at],
                )?;
                Ok(())
            })
            .await
    }

    pub async fn get_session(&self, token: &str) -> Result<Option<SessionToken>, DbError> {
        let token = token.to_string();
        self.db
            .read(move |c| {
                c.query_one(
                    "user_sessions.get",
                    "SELECT token, player_uuid, player_name, expires_at FROM user_sessions WHERE token = ?",
                    params![token],
                    |r| {
                        Ok(SessionToken {
                            token: r.get("token")?,
                            player_uuid: r.get("player_uuid")?,
                            player_name: r.get("player_name")?,
                            expires_at: r.get("expires_at")?,
                        })
                    },
                )
            })
            .await
    }

    /// Box-only housekeeping (no cron existed on the worker): drop expired
    /// sessions and challenges in bounded batches. Returns rows deleted.
    pub async fn prune_expired_auth_rows(&self, now_iso: &str, limit: i64) -> Result<usize, DbError> {
        let now_iso = now_iso.to_string();
        self.db
            .write(move |c| {
                let a = c.execute(
                    "user_sessions.prune",
                    "DELETE FROM user_sessions WHERE token IN (SELECT token FROM user_sessions WHERE expires_at < ? LIMIT ?)",
                    params![now_iso, limit],
                )?;
                let b = c.execute(
                    "auth_challenges.prune",
                    "DELETE FROM auth_challenges WHERE nonce IN (SELECT nonce FROM auth_challenges WHERE expires_at < ? LIMIT ?)",
                    params![now_iso, limit],
                )?;
                Ok(a + b)
            })
            .await
    }
}
