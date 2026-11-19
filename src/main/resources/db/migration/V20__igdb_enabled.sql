-- IGDB enable switch (2026-11-19): a disabled-but-stored state for the integration.
-- NULL = enabled (the afk_timeout_minutes pattern: rows predating the column
-- resolve to the code default), so the admin can pause the game detection
-- without wiping the Twitch credentials — flipping the toggle back on restores
-- the ladder together with its whole match cache.
ALTER TABLE server_profile
    ADD COLUMN igdb_enabled BOOLEAN;
