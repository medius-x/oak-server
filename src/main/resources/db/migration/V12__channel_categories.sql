-- Channel categories (Discord-style sections) + the manual sidebar order.
-- A category IS a channel row (type CATEGORY); TEXT/VOICE channels point at it
-- through parent_id. position is the sidebar order — dense after any reorder,
-- (position, id) is the sort key; the backfill preserves the id-ASC order the
-- list served before this migration. The CHECK must be dropped and re-added
-- (altering it in place is not possible), and H2 tests never run Flyway, so a
-- forgotten re-add only blows up on the live Postgres (the V4 lesson).
ALTER TABLE public.channels DROP CONSTRAINT channels_type_check;
ALTER TABLE public.channels ADD CONSTRAINT channels_type_check
    CHECK (type IN ('TEXT', 'VOICE', 'DM', 'GROUP_DM', 'DISCUSSION', 'CATEGORY'));

ALTER TABLE public.channels ADD COLUMN parent_id bigint;
ALTER TABLE public.channels ADD COLUMN position int NOT NULL DEFAULT 0;

UPDATE public.channels SET position = id;

ALTER TABLE public.channels ADD CONSTRAINT channels_parent_fk
    FOREIGN KEY (parent_id) REFERENCES public.channels (id);
CREATE INDEX idx_channels_parent ON public.channels (parent_id);
