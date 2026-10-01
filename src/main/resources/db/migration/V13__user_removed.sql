-- "Remove from server" (the reworked kick): permanent removal with account
-- anonymization. Mirrors `banned` (NOT NULL + default so the populated live
-- table back-fills); there is no un-remove.
ALTER TABLE users ADD COLUMN removed boolean NOT NULL DEFAULT false;
