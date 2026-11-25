-- Polls (2026-11-25). A poll rides on ONE dedicated message (content stays NULL —
-- the attachment-only precedent); message_id is UNIQUE so the relation is 1:1.
-- Votes are keyed by username (the game_sessions pattern: single-tenant instance,
-- removed accounts are tombstoned, names never reused).

CREATE TABLE polls (
    id                BIGSERIAL PRIMARY KEY,
    message_id        BIGINT NOT NULL UNIQUE REFERENCES messages(id) ON DELETE CASCADE,
    question          VARCHAR(300) NOT NULL,
    allow_multiple    BOOLEAN NOT NULL DEFAULT FALSE,
    anonymous         BOOLEAN NOT NULL DEFAULT FALSE,
    allow_vote_change BOOLEAN NOT NULL DEFAULT FALSE,
    ends_at           TIMESTAMP,          -- NULL = unlimited
    closed_at         TIMESTAMP           -- set when finished (timer expiry or early close)
);

CREATE TABLE poll_options (
    id       BIGSERIAL PRIMARY KEY,
    poll_id  BIGINT NOT NULL REFERENCES polls(id) ON DELETE CASCADE,
    position INT NOT NULL,
    text     VARCHAR(100) NOT NULL
);
CREATE INDEX idx_poll_options_poll ON poll_options(poll_id);

CREATE TABLE poll_votes (
    id        BIGSERIAL PRIMARY KEY,
    poll_id   BIGINT NOT NULL REFERENCES polls(id) ON DELETE CASCADE,
    option_id BIGINT NOT NULL REFERENCES poll_options(id) ON DELETE CASCADE,
    username  VARCHAR(255) NOT NULL,
    voted_at  TIMESTAMP NOT NULL
);
-- One user, one vote per option; a re-vote (allowed polls) replaces the whole set.
CREATE UNIQUE INDEX uq_poll_vote ON poll_votes(poll_id, option_id, username);
CREATE INDEX idx_poll_votes_poll ON poll_votes(poll_id);
