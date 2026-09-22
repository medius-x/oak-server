-- Pinned messages (Discord-style channel pins): four columns on `messages`.
-- `pinned` is NOT NULL DEFAULT false so the populated table back-fills in one pass (the
-- columnDefinition-default discipline, now expressed as a migration since Flyway owns the
-- schema). pinned_by/pinned_by_name/pinned_at are written and cleared together by
-- MessageService.pin/unpin; pinned_by_name is the denormalized actor username.
ALTER TABLE messages ADD COLUMN pinned boolean NOT NULL DEFAULT false;
ALTER TABLE messages ADD COLUMN pinned_by bigint;
ALTER TABLE messages ADD COLUMN pinned_by_name character varying(50);
ALTER TABLE messages ADD COLUMN pinned_at timestamp(6) with time zone;
