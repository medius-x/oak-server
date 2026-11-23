-- V24 (2026-11-23): the admin-selectable video codec for Janus voice rooms
-- (Server Settings → Video). NULL keeps the historical default — Janus
-- videoroom's own default, VP8 — so existing instances change nothing until
-- the admin picks. Applied to NEWLY CREATED rooms only: a room's codec is
-- fixed at creation and the room lives until the last member leaves.
ALTER TABLE server_profile ADD COLUMN video_codec TEXT;
