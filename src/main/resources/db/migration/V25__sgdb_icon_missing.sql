-- The "SteamGridDB has no official icon for this game" marker (2026-11-24,
-- the throttled-CDN post-mortem): distinguishes an ANSWERED miss from a
-- not-yet-delivered icon. The hourly retry sweep re-queues only the latter —
-- games the flaky transit still owes bytes to — and never re-asks the former.
ALTER TABLE igdb_games ADD COLUMN sgdb_icon_missing BOOLEAN;
