-- Box-only (PII pass, 2026-08): the Google profile display name is never
-- requested (the `profile` OAuth scope was dropped) and never read; clear
-- what history accumulated. Emails stay, but move to encrypted-at-rest via
-- `swctl encrypt-tokens` (a keyed operation, so not done in a migration).
UPDATE storage_accounts SET display_name = NULL;
UPDATE storage_link_sessions SET account_display_name = NULL;
