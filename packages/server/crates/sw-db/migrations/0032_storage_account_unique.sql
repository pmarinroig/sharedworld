-- Box-only (lane D): one storage_accounts row per (provider, external_account_id).
--
-- The pre-0.4.7 link flow looked a Google account up by its OIDC sub alone and
-- re-assigned owner_player_uuid to whoever linked last, so production may hold
-- duplicate rows for one Google account (and worlds pointing at either copy).
-- All duplicates of a group reference the SAME Google Drive, so re-pointing a
-- loser's worlds/objects at the surviving row is lossless. Winner choice: a
-- row an active world references beats recency.
CREATE TEMPORARY TABLE storage_account_dup_map AS
SELECT sa.id AS loser,
       (SELECT sa2.id
          FROM storage_accounts sa2
         WHERE sa2.provider = sa.provider
           AND sa2.external_account_id = sa.external_account_id
         ORDER BY EXISTS (SELECT 1 FROM worlds w
                           WHERE w.deleted_at IS NULL
                             AND w.storage_account_id = sa2.id) DESC,
                  sa2.updated_at DESC,
                  sa2.id DESC
         LIMIT 1) AS winner
  FROM storage_accounts sa;
DELETE FROM storage_account_dup_map WHERE loser = winner;

UPDATE worlds
   SET storage_account_id = (SELECT winner FROM storage_account_dup_map
                              WHERE loser = worlds.storage_account_id)
 WHERE storage_account_id IN (SELECT loser FROM storage_account_dup_map);

-- Content-addressed keys may exist under both rows; keep the winner's copy.
UPDATE OR IGNORE storage_objects
   SET storage_account_id = (SELECT winner FROM storage_account_dup_map
                              WHERE loser = storage_objects.storage_account_id)
 WHERE storage_account_id IN (SELECT loser FROM storage_account_dup_map);
DELETE FROM storage_objects
 WHERE storage_account_id IN (SELECT loser FROM storage_account_dup_map);

UPDATE OR IGNORE pending_blob_deletes
   SET storage_account_id = (SELECT winner FROM storage_account_dup_map
                              WHERE loser = pending_blob_deletes.storage_account_id)
 WHERE storage_account_id IN (SELECT loser FROM storage_account_dup_map);
DELETE FROM pending_blob_deletes
 WHERE storage_account_id IN (SELECT loser FROM storage_account_dup_map);

UPDATE storage_upload_sessions
   SET storage_account_id = (SELECT winner FROM storage_account_dup_map
                              WHERE loser = storage_upload_sessions.storage_account_id)
 WHERE storage_account_id IN (SELECT loser FROM storage_account_dup_map);

DELETE FROM storage_accounts
 WHERE id IN (SELECT loser FROM storage_account_dup_map);
DROP TABLE storage_account_dup_map;

CREATE UNIQUE INDEX IF NOT EXISTS idx_storage_accounts_provider_external
  ON storage_accounts (provider, external_account_id);
