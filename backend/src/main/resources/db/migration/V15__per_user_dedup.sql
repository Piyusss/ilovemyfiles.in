-- Dedup used to match the client-supplied SHA-256 against every user's
-- uploads. The server never verifies that hash (file bytes go straight to
-- storage), so anyone who knew a file's hash could attach another user's bytes
-- to their own account, and a client lying about its hash could make other
-- users' later uploads resolve to its bytes. Dedup is now scoped to the
-- uploading user, so a wrong hash can only ever mislead its sender.
ALTER TABLE physical_files
    ADD COLUMN user_id UUID REFERENCES users (id) ON DELETE CASCADE;

-- Existing entries go to the owner of the oldest file (trash included) that
-- uses them. Entries no file uses keep a NULL owner and drop out of dedup.
UPDATE physical_files pf
SET user_id = (SELECT f.user_id
               FROM files f
               WHERE f.physical_file_id = pf.id
               ORDER BY f.created_at
               LIMIT 1);

ALTER TABLE physical_files DROP CONSTRAINT physical_files_hash_key;
DROP INDEX IF EXISTS idx_physical_files_hash;
ALTER TABLE physical_files
    ADD CONSTRAINT uq_physical_files_user_hash UNIQUE (user_id, hash);

-- Deletion looks entries up by the object they describe.
CREATE INDEX idx_physical_files_storage_key ON physical_files (storage_key);

-- ref_count is no longer read or written: whether an object may be deleted is
-- decided by checking for referencing rows at delete time. The column stays
-- for now so the previous release keeps working during a rolling deploy.
