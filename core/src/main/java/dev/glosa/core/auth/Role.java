package dev.glosa.core.auth;

/**
 * What a user may do within their tenant.
 *
 * <p>Roles are per tenant: the same person in two tenants is two users with two
 * roles. There is deliberately no cross-tenant or platform-wide role, so no
 * token can ever grant access beyond the tenant it was issued for.
 *
 * <p>The names are persisted in {@code app_user.role} and constrained by a check
 * constraint, so renaming one needs a migration.
 */
public enum Role {

    /** Manages users and collections, on top of everything an editor may do. */
    ADMIN,

    /** Uploads and removes documents, and asks questions. */
    EDITOR,

    /** Asks questions and reads citations. Cannot change the corpus. */
    VIEWER;

    /** Authority name as Spring Security expects it. */
    public String authority() {
        return "ROLE_" + name();
    }
}
