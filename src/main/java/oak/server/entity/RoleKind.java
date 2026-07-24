package oak.server.entity;

/**
 * Marks the built-in roles. {@link #EVERYONE} is the implicit role every member has (never stored in
 * {@code user_roles} — it is folded into permission resolution unconditionally); {@link #OWNER} is the
 * single-holder superuser role (protected — cannot be deleted, renamed, or freely assigned); {@link #CUSTOM}
 * covers everything a user creates.
 */
public enum RoleKind {
    EVERYONE,
    OWNER,
    CUSTOM
}
