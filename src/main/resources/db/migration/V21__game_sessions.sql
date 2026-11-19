-- Game play sessions (2026-11-19, the achievements foundation): one row per
-- CONTINUOUS stretch of a user playing a PUBLISHED game — the server's roster
-- activity transitions open and close them (a hidden game is never reported,
-- so it never sessions: privacy and "no stats while hidden" by construction).
-- Hours per game/genre = SUM(ended_at - started_at); the achievements tier
-- will aggregate these against igdb_games.genres. A bare game id, no FK (the
-- afk_channel_id pattern).

CREATE TABLE game_sessions (
    id                  BIGSERIAL PRIMARY KEY,
    username            VARCHAR(255) NOT NULL,
    igdb_game_igdb_id   BIGINT NOT NULL,
    started_at          TIMESTAMP NOT NULL,
    ended_at            TIMESTAMP
);

CREATE INDEX idx_game_sessions_username ON game_sessions (username);
CREATE INDEX idx_game_sessions_game ON game_sessions (igdb_game_igdb_id);
