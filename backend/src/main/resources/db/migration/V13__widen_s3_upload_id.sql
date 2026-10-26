-- Cloudflare R2 returns multipart upload ids of ~343 characters, which overflowed
-- VARCHAR(255) and failed every upload. 1024 leaves plenty of room.
ALTER TABLE upload_sessions ALTER COLUMN s3_upload_id TYPE VARCHAR(1024);
