-- Before deleting a stored object the app now checks whether any file or
-- version still points at it (dedup lets rows share one object). Those lookups
-- are by storage_key, which lost its index when V6 dropped the unique
-- constraint on files.storage_key.
CREATE INDEX idx_files_storage_key ON files (storage_key);
CREATE INDEX idx_file_versions_storage_key ON file_versions (storage_key);
