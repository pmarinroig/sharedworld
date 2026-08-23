//! `StorageRepository`: link sessions, storage accounts, storage objects,
//! upload sessions, pending blob deletes.

use rusqlite::{params, Row};
use sw_contracts::{StorageLinkStatus, StorageProviderType};

use super::records::*;
use super::{placeholders, Repository};
use crate::error::DbError;

pub(crate) fn decrypt_opt(
    cipher: Option<&crate::token_cipher::TokenCipher>,
    v: Option<String>,
) -> Option<String> {
    match (cipher, v) {
        (Some(c), Some(s)) => c.decrypt(&s),
        (None, Some(s)) if crate::token_cipher::TokenCipher::is_encrypted(&s) => None,
        (None, v) => v,
        (_, None) => None,
    }
}

fn map_storage_account_with(
    r: &Row<'_>,
    cipher: Option<&crate::token_cipher::TokenCipher>,
) -> rusqlite::Result<StorageAccountRecord> {
    Ok(StorageAccountRecord {
        id: r.get("id")?,
        provider: provider_of(&r.get::<_, String>("provider")?),
        owner_player_uuid: r.get("owner_player_uuid")?,
        external_account_id: r.get("external_account_id")?,
        email: decrypt_opt(cipher, r.get("email")?),
        display_name: r.get("display_name")?,
        access_token: decrypt_opt(cipher, r.get("access_token")?),
        refresh_token: decrypt_opt(cipher, r.get("refresh_token")?),
        token_expires_at: r.get("token_expires_at")?,
        s3_endpoint: r.get("s3_endpoint")?,
        s3_region: r.get("s3_region")?,
        s3_bucket: r.get("s3_bucket")?,
        s3_key_prefix: r.get("s3_key_prefix")?,
        created_at: r.get("created_at")?,
        updated_at: r.get("updated_at")?,
    })
}

fn map_link_session(r: &Row<'_>) -> rusqlite::Result<StorageLinkSessionRecord> {
    Ok(StorageLinkSessionRecord {
        id: r.get("id")?,
        player_uuid: r.get("player_uuid")?,
        provider: provider_of(&r.get::<_, String>("provider")?),
        status: StorageLinkStatus::parse(&r.get::<_, String>("status")?).unwrap_or(StorageLinkStatus::Failed),
        auth_url: r.get("auth_url")?,
        state: r.get("state")?,
        linked_account_email: r.get("linked_account_email")?,
        account_display_name: r.get("account_display_name")?,
        storage_account_id: r.get("storage_account_id")?,
        error_message: r.get("error_message")?,
        created_at: r.get("created_at")?,
        expires_at: r.get("expires_at")?,
        completed_at: r.get("completed_at")?,
    })
}

fn map_upload_session(r: &Row<'_>) -> rusqlite::Result<StorageUploadSessionRecord> {
    Ok(StorageUploadSessionRecord {
        upload_id: r.get("upload_id")?,
        provider: provider_of(&r.get::<_, String>("provider")?),
        storage_account_id: r.get("storage_account_id")?,
        world_id: r.get("world_id")?,
        storage_key: r.get("storage_key")?,
        session_url: r.get("session_url")?,
        content_type: r.get("content_type")?,
        expected_size: r.get("expected_size")?,
        created_at: r.get("created_at")?,
        confirmed_at: r.get("confirmed_at")?,
    })
}

fn map_storage_object(r: &Row<'_>) -> rusqlite::Result<StorageObjectRecord> {
    Ok(StorageObjectRecord {
        provider: provider_of(&r.get::<_, String>("provider")?),
        storage_account_id: r.get("storage_account_id")?,
        storage_key: r.get("storage_key")?,
        object_id: r.get("object_id")?,
        content_type: r.get("content_type")?,
        size: r.get("size")?,
        created_at: r.get("created_at")?,
        updated_at: r.get("updated_at")?,
    })
}

const ACCOUNT_COLUMNS: &str = "id, provider, owner_player_uuid, external_account_id, email, display_name,
              access_token, refresh_token, token_expires_at, s3_endpoint, s3_region, s3_bucket, s3_key_prefix, created_at, updated_at";
const LINK_COLUMNS: &str = "id, player_uuid, provider, status, auth_url, state, linked_account_email,
              account_display_name, storage_account_id, error_message, created_at, expires_at, completed_at";
const UPLOAD_COLUMNS: &str = "upload_id, provider, storage_account_id, world_id, storage_key, session_url, content_type, expected_size, created_at, confirmed_at";

impl Repository {
    pub async fn create_storage_link_session(&self, s: StorageLinkSessionRecord) -> Result<(), DbError> {
        self.db
            .write(move |c| {
                c.execute(
                    "storage_link_sessions.insert",
                    &format!("INSERT INTO storage_link_sessions ({LINK_COLUMNS}) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"),
                    params![
                        s.id,
                        s.player_uuid,
                        s.provider.as_str(),
                        s.status.as_str(),
                        s.auth_url,
                        s.state,
                        s.linked_account_email,
                        s.account_display_name,
                        s.storage_account_id,
                        s.error_message,
                        s.created_at,
                        s.expires_at,
                        s.completed_at
                    ],
                )?;
                Ok(())
            })
            .await
    }

    pub async fn get_storage_link_session(
        &self,
        session_id: &str,
    ) -> Result<Option<StorageLinkSessionRecord>, DbError> {
        let id = session_id.to_string();
        self.db.read(move |c| get_link_session_in(c, &id)).await
    }

    pub async fn cancel_storage_link_session(
        &self,
        session_id: &str,
        completed_at: &str,
    ) -> Result<(), DbError> {
        let (id, at) = (session_id.to_string(), completed_at.to_string());
        self.db
            .write(move |c| {
                c.execute(
                    "storage_link_sessions.cancel",
                    "UPDATE storage_link_sessions SET status = 'cancelled', error_message = NULL, completed_at = ?
                     WHERE id = ? AND status = 'pending'",
                    params![at, id],
                )?;
                Ok(())
            })
            .await
    }

    pub async fn cancel_pending_storage_link_sessions(
        &self,
        player_uuid: &str,
        provider: StorageProviderType,
        except_session_id: &str,
        completed_at: &str,
    ) -> Result<(), DbError> {
        let (p, e, at) = (player_uuid.to_string(), except_session_id.to_string(), completed_at.to_string());
        self.db
            .write(move |c| {
                c.execute(
                    "storage_link_sessions.cancel_pending",
                    "UPDATE storage_link_sessions SET status = 'cancelled', error_message = NULL, completed_at = ?
                     WHERE player_uuid = ? AND provider = ? AND id <> ? AND status = 'pending'",
                    params![at, p, provider.as_str(), e],
                )?;
                Ok(())
            })
            .await
    }

    pub async fn update_storage_link_session(
        &self,
        session_id: &str,
        update: StorageLinkSessionUpdate,
    ) -> Result<(), DbError> {
        let id = session_id.to_string();
        self.db
            .write(move |c| {
                let Some(current) = get_link_session_in(c, &id)? else { return Ok(()) };
                // Present-but-null fields are explicit clears; absent keeps the current value.
                let status = update.status.unwrap_or(current.status);
                let email = update.linked_account_email.unwrap_or(current.linked_account_email);
                let err = update.error_message.unwrap_or(current.error_message);
                let acct = update.storage_account_id.unwrap_or(current.storage_account_id);
                let done = update.completed_at.unwrap_or(current.completed_at);
                c.execute(
                    "storage_link_sessions.update",
                    "UPDATE storage_link_sessions
                     SET status = ?, linked_account_email = ?, error_message = ?, storage_account_id = ?, completed_at = ?
                     WHERE id = ?",
                    params![status.as_str(), email, err, acct, done, id],
                )?;
                Ok(())
            })
            .await
    }

    pub async fn create_or_update_storage_account(
        &self,
        a: StorageAccountRecord,
    ) -> Result<StorageAccountRecord, DbError> {
        let out = a.clone();
        let a = match &self.token_cipher {
            Some(cipher) => StorageAccountRecord {
                access_token: a.access_token.as_deref().map(|t| cipher.encrypt(t)),
                refresh_token: a.refresh_token.as_deref().map(|t| cipher.encrypt(t)),
                // The email is PII: at rest it gets the same treatment as the
                // tokens, so DB files and backups hold no plaintext Google data.
                email: a.email.as_deref().map(|e| cipher.encrypt(e)),
                ..a
            },
            None => a,
        };
        self.db
            .write(move |c| {
                c.execute(
                    "storage_accounts.upsert",
                    &format!(
                        "INSERT INTO storage_accounts ({ACCOUNT_COLUMNS}) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                         ON CONFLICT(id) DO UPDATE SET
                           provider = excluded.provider,
                           owner_player_uuid = excluded.owner_player_uuid,
                           external_account_id = excluded.external_account_id,
                           email = excluded.email,
                           display_name = excluded.display_name,
                           access_token = excluded.access_token,
                           refresh_token = excluded.refresh_token,
                           token_expires_at = excluded.token_expires_at,
                           s3_endpoint = excluded.s3_endpoint,
                           s3_region = excluded.s3_region,
                           s3_bucket = excluded.s3_bucket,
                           s3_key_prefix = excluded.s3_key_prefix,
                           updated_at = excluded.updated_at"
                    ),
                    params![
                        a.id,
                        a.provider.as_str(),
                        a.owner_player_uuid,
                        a.external_account_id,
                        a.email,
                        a.display_name,
                        a.access_token,
                        a.refresh_token,
                        a.token_expires_at,
                        a.s3_endpoint,
                        a.s3_region,
                        a.s3_bucket,
                        a.s3_key_prefix,
                        a.created_at,
                        a.updated_at
                    ],
                )?;
                Ok(())
            })
            .await?;
        Ok(out)
    }

    pub async fn get_storage_account(
        &self,
        account_id: &str,
    ) -> Result<Option<StorageAccountRecord>, DbError> {
        let id = account_id.to_string();
        let cipher = self.token_cipher.clone();
        self.db.read(move |c| get_storage_account_with(c, &id, cipher.as_deref())).await
    }

    pub async fn find_storage_account_by_external_id(
        &self,
        provider: StorageProviderType,
        external_account_id: &str,
    ) -> Result<Option<StorageAccountRecord>, DbError> {
        let ext = external_account_id.to_string();
        let cipher = self.token_cipher.clone();
        self.db
            .read(move |c| {
                c.query_one(
                    "storage_accounts.by_external",
                    &format!("SELECT {ACCOUNT_COLUMNS} FROM storage_accounts WHERE provider = ? AND external_account_id = ?"),
                    params![provider.as_str(), ext],
                    |r| map_storage_account_with(r, cipher.as_deref()),
                )
            })
            .await
    }

    pub async fn find_storage_accounts_by_owner(
        &self,
        provider: StorageProviderType,
        owner_player_uuid: &str,
    ) -> Result<Vec<StorageAccountRecord>, DbError> {
        let owner = owner_player_uuid.to_string();
        let cipher = self.token_cipher.clone();
        self.db
            .read(move |c| {
                c.query(
                    "storage_accounts.by_owner",
                    &format!(
                        "SELECT {ACCOUNT_COLUMNS} FROM storage_accounts
                         WHERE provider = ? AND owner_player_uuid = ?
                         ORDER BY updated_at DESC, id DESC"
                    ),
                    params![provider.as_str(), owner],
                    |r| map_storage_account_with(r, cipher.as_deref()),
                )
            })
            .await
    }

    pub async fn upsert_storage_object(&self, o: StorageObjectRecord) -> Result<(), DbError> {
        self.db
            .write(move |c| {
                c.execute(
                    "storage_objects.upsert",
                    "INSERT INTO storage_objects (provider, storage_account_id, storage_key, object_id, content_type, size, created_at, updated_at)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                     ON CONFLICT(provider, storage_account_id, storage_key) DO UPDATE SET
                       object_id = excluded.object_id,
                       content_type = excluded.content_type,
                       size = excluded.size,
                       updated_at = excluded.updated_at",
                    params![
                        o.provider.as_str(),
                        o.storage_account_id,
                        o.storage_key,
                        o.object_id,
                        o.content_type,
                        o.size,
                        o.created_at,
                        o.updated_at
                    ],
                )?;
                Ok(())
            })
            .await
    }

    pub async fn get_storage_object(
        &self,
        provider: StorageProviderType,
        storage_account_id: &str,
        storage_key: &str,
    ) -> Result<Option<StorageObjectRecord>, DbError> {
        let (a, k) = (storage_account_id.to_string(), storage_key.to_string());
        self.db
            .read(move |c| {
                c.query_one(
                    "storage_objects.get",
                    "SELECT provider, storage_account_id, storage_key, object_id, content_type, size, created_at, updated_at
                     FROM storage_objects WHERE provider = ? AND storage_account_id = ? AND storage_key = ?",
                    params![provider.as_str(), a, k],
                    map_storage_object,
                )
            })
            .await
    }

    /// Which of the given keys have object rows — one batched query.
    pub async fn list_existing_storage_keys(
        &self,
        provider: StorageProviderType,
        storage_account_id: &str,
        storage_keys: &[String],
    ) -> Result<std::collections::HashSet<String>, DbError> {
        let a = storage_account_id.to_string();
        let keys = storage_keys.to_vec();
        self.db
            .read(move |c| {
                let mut out = std::collections::HashSet::new();
                for chunk in keys.chunks(80) {
                    let mut p: Vec<&dyn rusqlite::ToSql> = Vec::with_capacity(chunk.len() + 2);
                    let prov = provider.as_str();
                    p.push(&prov);
                    p.push(&a);
                    for k in chunk {
                        p.push(k);
                    }
                    let rows = c.query(
                        "storage_objects.existing_keys",
                        &format!(
                            "SELECT storage_key FROM storage_objects
                             WHERE provider = ? AND storage_account_id = ? AND storage_key IN ({})",
                            placeholders(chunk.len())
                        ),
                        p.as_slice(),
                        |r| r.get::<_, String>(0),
                    )?;
                    out.extend(rows);
                }
                Ok(out)
            })
            .await
    }

    /// Object rows for many keys at once (relay grants need the file ids).
    pub async fn get_storage_objects_batch(
        &self,
        provider: StorageProviderType,
        storage_account_id: &str,
        storage_keys: &[String],
    ) -> Result<Vec<StorageObjectRecord>, DbError> {
        let a = storage_account_id.to_string();
        let keys = storage_keys.to_vec();
        self.db
            .read(move |c| {
                c.query(
                    "storage_objects.batch",
                    "SELECT provider, storage_account_id, storage_key, object_id, content_type, size, created_at, updated_at
                     FROM storage_objects
                     WHERE provider = ? AND storage_account_id = ? AND storage_key IN (SELECT value FROM json_each(?))",
                    params![provider.as_str(), a, super::json_list(&keys)],
                    map_storage_object,
                )
            })
            .await
    }

    pub async fn delete_storage_object(
        &self,
        provider: StorageProviderType,
        storage_account_id: &str,
        storage_key: &str,
    ) -> Result<(), DbError> {
        let (a, k) = (storage_account_id.to_string(), storage_key.to_string());
        self.db
            .write(move |c| {
                c.execute(
                    "storage_objects.delete",
                    "DELETE FROM storage_objects WHERE provider = ? AND storage_account_id = ? AND storage_key = ?",
                    params![provider.as_str(), a, k],
                )?;
                Ok(())
            })
            .await
    }

    pub async fn create_upload_session(&self, r: StorageUploadSessionRecord) -> Result<(), DbError> {
        self.db
            .write(move |c| {
                c.execute(
                    "storage_upload_sessions.insert",
                    &format!("INSERT INTO storage_upload_sessions ({UPLOAD_COLUMNS}) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"),
                    params![
                        r.upload_id,
                        r.provider.as_str(),
                        r.storage_account_id,
                        r.world_id,
                        r.storage_key,
                        r.session_url,
                        r.content_type,
                        r.expected_size,
                        r.created_at,
                        r.confirmed_at
                    ],
                )?;
                Ok(())
            })
            .await
    }

    pub async fn get_upload_session(
        &self,
        upload_id: &str,
    ) -> Result<Option<StorageUploadSessionRecord>, DbError> {
        let id = upload_id.to_string();
        self.db
            .read(move |c| {
                c.query_one(
                    "storage_upload_sessions.get",
                    &format!("SELECT {UPLOAD_COLUMNS} FROM storage_upload_sessions WHERE upload_id = ?"),
                    params![id],
                    map_upload_session,
                )
            })
            .await
    }

    pub async fn mark_upload_session_confirmed(
        &self,
        upload_id: &str,
        confirmed_at: &str,
    ) -> Result<(), DbError> {
        let (id, at) = (upload_id.to_string(), confirmed_at.to_string());
        self.db
            .write(move |c| {
                c.execute(
                    "storage_upload_sessions.confirm",
                    "UPDATE storage_upload_sessions SET confirmed_at = ? WHERE upload_id = ?",
                    params![at, id],
                )?;
                Ok(())
            })
            .await
    }

    pub async fn delete_upload_session(&self, upload_id: &str) -> Result<(), DbError> {
        let id = upload_id.to_string();
        self.db
            .write(move |c| {
                c.execute(
                    "storage_upload_sessions.delete",
                    "DELETE FROM storage_upload_sessions WHERE upload_id = ?",
                    params![id],
                )?;
                Ok(())
            })
            .await
    }

    pub async fn list_unconfirmed_upload_sessions_before(
        &self,
        provider: StorageProviderType,
        storage_account_id: &str,
        created_before: &str,
        limit: i64,
    ) -> Result<Vec<StorageUploadSessionRecord>, DbError> {
        let (a, before) = (storage_account_id.to_string(), created_before.to_string());
        self.db
            .read(move |c| {
                c.query(
                    "storage_upload_sessions.unconfirmed_before",
                    &format!(
                        "SELECT {UPLOAD_COLUMNS} FROM storage_upload_sessions
                         WHERE provider = ? AND storage_account_id = ? AND confirmed_at IS NULL AND created_at < ?
                         ORDER BY created_at ASC
                         LIMIT ?"
                    ),
                    params![provider.as_str(), a, before, limit],
                    map_upload_session,
                )
            })
            .await
    }

    pub async fn delete_confirmed_upload_sessions_before(
        &self,
        provider: StorageProviderType,
        storage_account_id: &str,
        confirmed_before: &str,
        limit: i64,
    ) -> Result<(), DbError> {
        let (a, before) = (storage_account_id.to_string(), confirmed_before.to_string());
        self.db
            .write(move |c| {
                c.execute(
                    "storage_upload_sessions.delete_confirmed_before",
                    "DELETE FROM storage_upload_sessions WHERE upload_id IN (
                       SELECT upload_id FROM storage_upload_sessions
                       WHERE provider = ? AND storage_account_id = ? AND confirmed_at IS NOT NULL AND confirmed_at < ?
                       LIMIT ?
                     )",
                    params![provider.as_str(), a, before, limit],
                )?;
                Ok(())
            })
            .await
    }

    pub async fn enqueue_pending_blob_delete(
        &self,
        provider: StorageProviderType,
        storage_account_id: &str,
        storage_key: &str,
        enqueued_at: &str,
    ) -> Result<(), DbError> {
        self.enqueue_pending_blob_deletes(
            provider,
            storage_account_id,
            &[storage_key.to_string()],
            enqueued_at,
        )
        .await
    }

    pub async fn enqueue_pending_blob_deletes(
        &self,
        provider: StorageProviderType,
        storage_account_id: &str,
        storage_keys: &[String],
        enqueued_at: &str,
    ) -> Result<(), DbError> {
        if storage_keys.is_empty() {
            return Ok(());
        }
        let (a, at, keys) = (storage_account_id.to_string(), enqueued_at.to_string(), storage_keys.to_vec());
        self.db
            .write(move |c| {
                for k in keys {
                    c.execute(
                        "pending_blob_deletes.enqueue",
                        "INSERT INTO pending_blob_deletes (provider, storage_account_id, storage_key, enqueued_at)
                         VALUES (?, ?, ?, ?)
                         ON CONFLICT (provider, storage_account_id, storage_key) DO NOTHING",
                        params![provider.as_str(), a, k, at],
                    )?;
                }
                Ok(())
            })
            .await
    }

    pub async fn list_pending_blob_deletes(
        &self,
        provider: StorageProviderType,
        storage_account_id: &str,
        limit: i64,
    ) -> Result<Vec<PendingBlobDeleteEntry>, DbError> {
        let a = storage_account_id.to_string();
        self.db
            .read(move |c| {
                c.query(
                    "pending_blob_deletes.list",
                    "SELECT storage_key, attempts, enqueued_at FROM pending_blob_deletes
                     WHERE provider = ? AND storage_account_id = ?
                     ORDER BY enqueued_at ASC
                     LIMIT ?",
                    params![provider.as_str(), a, limit],
                    |r| {
                        Ok(PendingBlobDeleteEntry {
                            storage_key: r.get("storage_key")?,
                            attempts: r.get("attempts")?,
                            enqueued_at: r.get("enqueued_at")?,
                        })
                    },
                )
            })
            .await
    }

    /// 0.4.5 cron drain: due deletes across accounts (backoff 5 min · 2^(n-1), cap 24 h).
    pub async fn list_due_pending_blob_deletes(
        &self,
        now: &str,
        limit: i64,
    ) -> Result<Vec<PendingBlobDeleteRecord>, DbError> {
        let now = now.to_string();
        self.db
            .read(move |c| {
                c.query(
                    "pending_blob_deletes.due",
                    "SELECT provider, storage_account_id, storage_key, attempts, enqueued_at
                     FROM pending_blob_deletes
                     WHERE last_attempt_at IS NULL
                        OR datetime(last_attempt_at, '+' || MIN(1440, 5 * (1 << MIN(MAX(attempts - 1, 0), 12))) || ' minutes') <= datetime(?)
                     ORDER BY attempts ASC, enqueued_at ASC
                     LIMIT ?",
                    params![now, limit],
                    |r| {
                        Ok(PendingBlobDeleteRecord {
                            provider: provider_of(&r.get::<_, String>("provider")?),
                            storage_account_id: r.get("storage_account_id")?,
                            storage_key: r.get("storage_key")?,
                            attempts: r.get("attempts")?,
                            enqueued_at: r.get("enqueued_at")?,
                        })
                    },
                )
            })
            .await
    }

    pub async fn count_pending_blob_deletes(&self) -> Result<i64, DbError> {
        self.db
            .read(|c| {
                Ok(c.query_one(
                    "pending_blob_deletes.count",
                    "SELECT COUNT(*) FROM pending_blob_deletes",
                    [],
                    |r| r.get(0),
                )?
                .unwrap_or(0))
            })
            .await
    }

    pub async fn delete_pending_blob_delete(
        &self,
        provider: StorageProviderType,
        storage_account_id: &str,
        storage_key: &str,
    ) -> Result<(), DbError> {
        let (a, k) = (storage_account_id.to_string(), storage_key.to_string());
        self.db
            .write(move |c| {
                c.execute(
                    "pending_blob_deletes.delete",
                    "DELETE FROM pending_blob_deletes WHERE provider = ? AND storage_account_id = ? AND storage_key = ?",
                    params![provider.as_str(), a, k],
                )?;
                Ok(())
            })
            .await
    }

    /// Account unlink / delete-account: removes every storage account row a
    /// player owns for a provider (orphans from linking a second Google
    /// account included).
    pub async fn delete_storage_accounts_for_owner(
        &self,
        provider: StorageProviderType,
        owner_player_uuid: &str,
    ) -> Result<(), DbError> {
        let owner = owner_player_uuid.to_string();
        self.db
            .write(move |c| {
                c.execute(
                    "storage_accounts.delete_for_owner",
                    "DELETE FROM storage_accounts WHERE provider = ? AND owner_player_uuid = ?",
                    params![provider.as_str(), owner],
                )?;
                Ok(())
            })
            .await
    }

    pub async fn delete_storage_objects_for_account(
        &self,
        provider: StorageProviderType,
        storage_account_id: &str,
    ) -> Result<(), DbError> {
        let a = storage_account_id.to_string();
        self.db
            .write(move |c| {
                c.execute(
                    "storage_objects.delete_for_account",
                    "DELETE FROM storage_objects WHERE provider = ? AND storage_account_id = ?",
                    params![provider.as_str(), a],
                )?;
                Ok(())
            })
            .await
    }

    pub async fn delete_pending_blob_deletes_for_account(
        &self,
        provider: StorageProviderType,
        storage_account_id: &str,
    ) -> Result<(), DbError> {
        let a = storage_account_id.to_string();
        self.db
            .write(move |c| {
                c.execute(
                    "pending_blob_deletes.delete_for_account",
                    "DELETE FROM pending_blob_deletes WHERE provider = ? AND storage_account_id = ?",
                    params![provider.as_str(), a],
                )?;
                Ok(())
            })
            .await
    }

    pub async fn delete_storage_link_sessions_for_player(&self, player_uuid: &str) -> Result<(), DbError> {
        let p = player_uuid.to_string();
        self.db
            .write(move |c| {
                c.execute(
                    "storage_link_sessions.delete_for_player",
                    "DELETE FROM storage_link_sessions WHERE player_uuid = ?",
                    params![p],
                )?;
                Ok(())
            })
            .await
    }

    pub async fn bump_pending_blob_delete_attempt(
        &self,
        provider: StorageProviderType,
        storage_account_id: &str,
        storage_key: &str,
        attempted_at: &str,
    ) -> Result<(), DbError> {
        let (a, k, at) = (storage_account_id.to_string(), storage_key.to_string(), attempted_at.to_string());
        self.db
            .write(move |c| {
                c.execute(
                    "pending_blob_deletes.bump",
                    "UPDATE pending_blob_deletes SET attempts = attempts + 1, last_attempt_at = ?
                     WHERE provider = ? AND storage_account_id = ? AND storage_key = ?",
                    params![at, provider.as_str(), a, k],
                )?;
                Ok(())
            })
            .await
    }
}

pub(crate) fn get_storage_account_in(
    c: &crate::pool::Conn<'_>,
    id: &str,
) -> Result<Option<StorageAccountRecord>, DbError> {
    get_storage_account_with(c, id, None)
}

pub(crate) fn get_storage_account_with(
    c: &crate::pool::Conn<'_>,
    id: &str,
    cipher: Option<&crate::token_cipher::TokenCipher>,
) -> Result<Option<StorageAccountRecord>, DbError> {
    c.query_one(
        "storage_accounts.get",
        &format!("SELECT {ACCOUNT_COLUMNS} FROM storage_accounts WHERE id = ?"),
        params![id],
        |r| map_storage_account_with(r, cipher),
    )
}

/// `swctl encrypt-tokens`: convert plaintext token + email columns to `enc:v1:`.
pub fn encrypt_plaintext_tokens(
    c: &crate::pool::Conn<'_>,
    cipher: &crate::token_cipher::TokenCipher,
) -> Result<usize, DbError> {
    let rows = c.query(
        "storage_accounts.all_tokens",
        "SELECT id, access_token, refresh_token, email FROM storage_accounts",
        [],
        |r| {
            Ok((
                r.get::<_, String>(0)?,
                r.get::<_, Option<String>>(1)?,
                r.get::<_, Option<String>>(2)?,
                r.get::<_, Option<String>>(3)?,
            ))
        },
    )?;
    let mut n = 0;
    for (id, access, refresh, email) in rows {
        let needs = |v: &Option<String>| {
            v.as_deref().is_some_and(|s| !crate::token_cipher::TokenCipher::is_encrypted(s))
        };
        if !needs(&access) && !needs(&refresh) && !needs(&email) {
            continue;
        }
        c.execute(
            "storage_accounts.encrypt_tokens",
            "UPDATE storage_accounts SET access_token = ?, refresh_token = ?, email = ? WHERE id = ?",
            params![
                access.as_deref().map(|t| cipher.encrypt(t)),
                refresh.as_deref().map(|t| cipher.encrypt(t)),
                email.as_deref().map(|t| cipher.encrypt(t)),
                id
            ],
        )?;
        n += 1;
    }
    Ok(n)
}

fn get_link_session_in(
    c: &crate::pool::Conn<'_>,
    id: &str,
) -> Result<Option<StorageLinkSessionRecord>, DbError> {
    c.query_one(
        "storage_link_sessions.get",
        &format!("SELECT {LINK_COLUMNS} FROM storage_link_sessions WHERE id = ?"),
        params![id],
        map_link_session,
    )
}
