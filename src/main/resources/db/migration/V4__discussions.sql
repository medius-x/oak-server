-- Discussions (threads): a discussion is a Channel of type DISCUSSION (append at the enum END)
-- plus this companion row freezing the origin-message snapshot. The CHECK on channels.type is
-- hand-written by Hibernate at first schema generation and NEVER updated by ddl-auto=update —
-- adding an enum value on a populated table requires the manual DROP/ADD (the DM precedent).
ALTER TABLE channels DROP CONSTRAINT channels_type_check;
ALTER TABLE channels ADD CONSTRAINT channels_type_check
    CHECK (type IN ('TEXT','VOICE','DM','GROUP_DM','DISCUSSION'));

CREATE TABLE discussions (
    id bigint NOT NULL,
    channel_id bigint NOT NULL,
    parent_channel_id bigint NOT NULL,
    origin_message_id bigint NOT NULL,
    origin_snippet character varying(200),
    origin_author_id bigint,
    origin_author_name character varying(50),
    origin_created_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone NOT NULL,
    last_message_at timestamp(6) with time zone,
    CONSTRAINT discussions_pkey PRIMARY KEY (id),
    CONSTRAINT discussions_channel_unique UNIQUE (channel_id),
    CONSTRAINT discussions_origin_unique UNIQUE (origin_message_id)
);

CREATE INDEX idx_discussions_parent ON discussions (parent_channel_id);
