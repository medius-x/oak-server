-- A consumed invite no longer exists: the single-use claim became an atomic DELETE
-- (InviteRepository.claim), so the used/used_by/used_at columns — the audit trail of
-- spent links — have nothing to record. Revoked/expired rows are swept by InvitesCleanup.
ALTER TABLE invites DROP COLUMN used;
ALTER TABLE invites DROP COLUMN used_by;
ALTER TABLE invites DROP COLUMN used_at;
