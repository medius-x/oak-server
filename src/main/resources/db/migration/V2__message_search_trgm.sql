-- Message-search indexes (#481): substring ILIKE matching over content and the
-- attachments JSON text, backed by pg_trgm GIN indexes.
-- Idempotent on purpose (IF NOT EXISTS): safe to re-run, and harmless if the
-- extension/indexes had been created manually before Flyway was adopted.
--
-- NOTE for large production tables: this runs INSIDE Flyway's migration
-- transaction, so CREATE INDEX CONCURRENTLY (which cannot run in a transaction)
-- is not usable here as-is. On a database big enough for index builds to lock
-- writers meaningfully, split into a per-script config
-- (V2__message_search_trgm.sql.conf with executeInTransaction=false) and switch
-- to CONCURRENTLY. The dev instance is small — a plain build is instantaneous.

CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_messages_content_trgm
    ON public.messages USING GIN (content gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_messages_attachments_trgm
    ON public.messages USING GIN (attachments gin_trgm_ops);
