-- Voice rooms with a participant limit (2026-11-18): channels.user_limit is NULL (= no limit)
-- or 1..99, meaningful on VOICE rows only. The gate lives in RoomController (GET /room 403 when
-- the room is full); the CHECK is defense-in-depth for hand-edited rows — the service validates
-- the same range before writing.
ALTER TABLE channels ADD COLUMN user_limit INTEGER;
ALTER TABLE channels ADD CONSTRAINT chk_channels_user_limit CHECK (user_limit IS NULL OR user_limit BETWEEN 1 AND 99);
