package dev.glosa.core.tenant;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Tenant the current unit of work belongs to.
 *
 * <p>The value is set once per request from the verified access token, and read
 * again when a database connection is handed out so that Row Level Security can
 * be applied to it. Nothing else should need to know about it: queries do not
 * take a tenant argument, because the database already constrains them.
 *
 * <p>The context is bound to the current thread and must be cleared when the
 * unit of work ends. Threads are pooled, so a context left behind would be
 * inherited by an unrelated request.
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    /** Current tenant, or empty when the work is not scoped to one. */
    public static Optional<UUID> currentTenantId() {
        return Optional.ofNullable(CURRENT.get());
    }

    /**
     * Current tenant, for code that cannot do anything useful without one.
     *
     * @throws IllegalStateException if no tenant is bound, which signals a bug
     *                               rather than a client error: reaching this
     *                               point unscoped means a filter or a
     *                               wrapper was skipped
     */
    public static UUID requireTenantId() {
        UUID tenantId = CURRENT.get();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant is bound to the current thread");
        }
        return tenantId;
    }

    /**
     * Runs the action scoped to the given tenant, restoring whatever context was
     * in place before. Used by the ingestion worker, which walks jobs belonging
     * to different tenants on the same thread.
     */
    public static <T> T callWith(UUID tenantId, Supplier<T> action) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(action, "action");
        UUID previous = CURRENT.get();
        CURRENT.set(tenantId);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /** {@link #callWith(UUID, Supplier)} for actions that return nothing. */
    public static void runWith(UUID tenantId, Runnable action) {
        Objects.requireNonNull(action, "action");
        callWith(tenantId, () -> {
            action.run();
            return null;
        });
    }

    /**
     * Binds a tenant to the current thread. Prefer {@link #callWith} where the
     * scope is known; this exists for the request filter, whose scope is the
     * request itself and which clears the context in a finally block.
     */
    public static void bind(UUID tenantId) {
        CURRENT.set(Objects.requireNonNull(tenantId, "tenantId"));
    }

    /** Unbinds the tenant. Safe to call when nothing is bound. */
    public static void clear() {
        CURRENT.remove();
    }
}
