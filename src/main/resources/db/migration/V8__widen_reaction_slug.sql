-- Phase 3 (custom emojis as reactions): a custom-emoji reaction rides as a
-- colon-wrapped ":name:" token in the same `reaction` column. The catalog name
-- grammar allows up to 32 chars, so the token needs up to 34 — the original
-- varchar(32) (sized for bare built-in slugs) no longer fits. A typmod widen is
-- metadata-only in PostgreSQL: no table rewrite, and the unique
-- (message_id, user_id, reaction) index is unaffected. H2 tests regenerate the
-- schema from the entity and never see this file.

ALTER TABLE public.message_reactions ALTER COLUMN reaction TYPE character varying(64);
