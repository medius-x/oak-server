-- The user's LAST SEEN moment (2026-11-19, "just in case" for the future
-- achievements content): stamped when the user actually SHOWS UP online (the
-- first STOMP connect of their session set — NOT the REST login, which
-- refresh tokens keep alive for weeks without a real visit). Null on rows
-- predating the column until the user's next visit.

ALTER TABLE users
    ADD COLUMN last_seen_at TIMESTAMP;
