-- The SteamGridDB icon layer teardown (user decision 2026-11-24, after the
-- 0.8.0–0.8.7 delivery saga on a transit that strangles the CDN): the whole
-- feature is gone — code, endpoints, capabilities — and its columns go with
-- it. The IGDB game stack (detection, covers, activity) is untouched.
ALTER TABLE igdb_games
    DROP COLUMN IF EXISTS sgdb_icon_url,
    DROP COLUMN IF EXISTS sgdb_icon_bytes,
    DROP COLUMN IF EXISTS sgdb_icon_mime,
    DROP COLUMN IF EXISTS sgdb_icon_missing,
    DROP COLUMN IF EXISTS sgdb_icon_partial;

ALTER TABLE server_profile
    DROP COLUMN IF EXISTS sgdb_api_key,
    DROP COLUMN IF EXISTS sgdb_enabled;
