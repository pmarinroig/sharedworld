-- Box-only (0.5.0): user-supplied S3-compatible buckets as a storage provider.
--
-- An S3 account reuses the storage_accounts shape: external_account_id holds
-- the access key id (so the 0032 uniqueness gives "already linked elsewhere"
-- conflicts for free), the secret key rides the encrypted access_token column,
-- and email doubles as the human-readable label shown in the client. The
-- connection details below are S3-only and NULL for Google Drive rows.
ALTER TABLE storage_accounts ADD COLUMN s3_endpoint TEXT;
ALTER TABLE storage_accounts ADD COLUMN s3_region TEXT;
ALTER TABLE storage_accounts ADD COLUMN s3_bucket TEXT;
ALTER TABLE storage_accounts ADD COLUMN s3_key_prefix TEXT;
