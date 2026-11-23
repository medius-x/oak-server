-- SteamGridDB icons (2026-11-19): the instance's SGDB API key (write-only,
-- one key per account, free) and the picked icon cached on each game card —
-- IGDB has no square app icons; SGDB's official-style list is queried once
-- per game by the Steam appid. The BYTES live here too: the CDN is unreachable
-- from some CLIENT machines (DPI/proxy — the broken-picture smoke, 2026-11-23),
-- so the server proxies the icon and clients never leave home.
ALTER TABLE server_profile ADD COLUMN sgdb_api_key TEXT;

ALTER TABLE igdb_games ADD COLUMN sgdb_icon_url TEXT;

ALTER TABLE igdb_games ADD COLUMN sgdb_icon_bytes bytea;

ALTER TABLE igdb_games ADD COLUMN sgdb_icon_mime TEXT;

-- The icon layer's PAUSE switch (the IGDB pattern): false = icons are neither
-- resolved nor SERVED (a cached value stays stored but answers nothing); null
-- on rows predating the column resolves to enabled.
ALTER TABLE server_profile ADD COLUMN sgdb_enabled BOOLEAN;

-- The IGDB cover-thumbnail cache (the same client-CDN-unreachable lesson as
-- the icons: the panel's fallback picture must come from OUR server too).
ALTER TABLE igdb_games ADD COLUMN cover_thumb_bytes bytea;

ALTER TABLE igdb_games ADD COLUMN cover_thumb_mime TEXT;
