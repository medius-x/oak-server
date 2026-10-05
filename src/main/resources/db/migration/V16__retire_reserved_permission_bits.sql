-- Retire EMBED_LINKS (bit 14 = 16384) and USE_EXTERNAL_EMOJIS (bit 17 = 131072):
-- never enforced, meaningless on a single-tenant instance (user decision 2026-10-02).
-- The enum constants became RESERVED_* placeholders keeping their ordinals; this clears
-- the stale bits from EVERY persisted mask so a future repurpose of a reserved bit can
-- never inherit an old grant (EMBED_LINKS was seeded into @everyone defaults — every
-- existing database carries it). Roles are re-serialized by the app on next save anyway;
-- this only strips the two retired bits, leaving all others untouched.
UPDATE roles SET permissions = permissions & ~(16384 | 131072);
UPDATE channel_role_overrides SET allow = allow & ~(16384 | 131072),
                                  deny  = deny  & ~(16384 | 131072);
UPDATE channel_user_overrides SET allow = allow & ~(16384 | 131072),
                                  deny  = deny  & ~(16384 | 131072);
