-- The AFK-channel sweep settings (the invite-TTL pattern: a nullable column on the
-- singleton profile; afk_channel_id = NULL disables the feature). No FK on purpose —
-- the channel-deleted reset is enforced in code (ChannelService.delete), and H2 tests
-- regenerate the schema from entities anyway.
ALTER TABLE server_profile ADD COLUMN afk_channel_id bigint;
ALTER TABLE server_profile ADD COLUMN afk_timeout_minutes integer DEFAULT 30;
