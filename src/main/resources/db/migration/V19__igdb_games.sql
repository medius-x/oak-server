-- IGDB game auto-detection, round 1 (2026-11-19): the instance's Twitch application
-- credentials (the admin registers an app at dev.twitch.tv and pastes the pair in
-- Server Settings → IGDB; either column null = the integration is off, its endpoints
-- answer 503 and its capability flag is not reported) + the local cache of resolved
-- games, so the match ladder never re-queries IGDB for a key it has already resolved.
-- Misses are cached too (NULL game id) behind a TTL re-check; hits are kept forever
-- (a game's card — name, cover, genres — is stable).

ALTER TABLE server_profile
    ADD COLUMN igdb_client_id VARCHAR(64),
    ADD COLUMN igdb_client_secret VARCHAR(128);

CREATE TABLE igdb_games (
    igdb_id             BIGINT PRIMARY KEY,
    name                VARCHAR(256) NOT NULL,
    cover_image_id      VARCHAR(64),
    genres              TEXT NOT NULL,
    first_release_date  BIGINT,
    cached_at           TIMESTAMP NOT NULL
);

CREATE TABLE igdb_match_cache (
    match_key           VARCHAR(256) PRIMARY KEY,
    igdb_game_igdb_id   BIGINT,
    resolved_at         TIMESTAMP NOT NULL
);
