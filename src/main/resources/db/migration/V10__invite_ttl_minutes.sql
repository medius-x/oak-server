-- The invite TTL is authored in MINUTES (the settings field asks for them), not hours:
-- the wire, the profile column and the mint-time expiry all carry minutes now.
-- Existing rows keep their span — hours are converted before the rename.
UPDATE server_profile SET invite_ttl_hours = invite_ttl_hours * 60;
ALTER TABLE server_profile RENAME COLUMN invite_ttl_hours TO invite_ttl_minutes;
