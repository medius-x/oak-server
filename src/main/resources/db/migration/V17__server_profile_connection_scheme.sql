-- The advertised connection scheme (http | https; NULL = the historical default
-- http). Invite links carry it (InviteController.buildLink): a TLS deployment
-- (the Caddy entry point of the "full" compose profile, provisioning round B)
-- sets it to https once via PATCH /api/server/connection.
ALTER TABLE server_profile ADD COLUMN connection_scheme varchar(8);
