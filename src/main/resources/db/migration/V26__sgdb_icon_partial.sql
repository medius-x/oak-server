-- The partial-bytes accumulator (2026-11-24, the burst-throttling transit
-- post-mortem): some hostings' route to the SGDB CDN lets ~20 KB through per
-- connection and then strangles the flow to ~4 B/s. The icon lane therefore
-- keeps what it got (Range-resume material): sgdb_icon_url doubles as the
-- pending marker (URL written, bytes still null) and each attempt continues
-- from the accumulated offset until the file completes. Cleared on delivery.
ALTER TABLE igdb_games ADD COLUMN sgdb_icon_partial BYTEA;
