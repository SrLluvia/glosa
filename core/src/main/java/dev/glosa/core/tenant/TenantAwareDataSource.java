package dev.glosa.core.tenant;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.ConnectionProxy;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Carries the current tenant onto every database connection, so the Row Level
 * Security policies have something to compare rows against.
 *
 * <p>The tenant is written to the {@code glosa.tenant_id} run-time parameter the
 * moment a connection is handed out, and cleared again when it is returned to
 * the pool. Setting it on every checkout is what makes this safe: a connection
 * can never be reused with a previous borrower's tenant still attached, even if
 * the reset on return was skipped because the connection broke.
 *
 * <p>When no tenant is bound the parameter is set to the empty string, which the
 * {@code current_tenant_id()} function maps to {@code NULL}, and a {@code NULL}
 * context matches no rows. Unscoped work therefore sees nothing instead of
 * seeing everything.
 *
 * <p>The tenant is captured at checkout. Code that changes the bound tenant in
 * the middle of a transaction does not change the connection already in use,
 * which is why {@link TenantContext#callWith} wraps whole units of work.
 */
public class TenantAwareDataSource extends DelegatingDataSource {

    private static final Logger log = LoggerFactory.getLogger(TenantAwareDataSource.class);

    /**
     * Set as a session parameter rather than with {@code SET LOCAL}, so that
     * work outside an explicit transaction is scoped too. The value is bound as
     * a statement parameter, never concatenated into the SQL.
     */
    private static final String APPLY_TENANT_SQL = "select set_config('glosa.tenant_id', ?, false)";

    private static final String NO_TENANT = "";

    public TenantAwareDataSource(DataSource targetDataSource) {
        super(targetDataSource);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return scope(obtainTargetDataSource().getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return scope(obtainTargetDataSource().getConnection(username, password));
    }

    private Connection scope(Connection connection) throws SQLException {
        try {
            applyTenant(connection, TenantContext.currentTenantId().orElse(null));
        } catch (SQLException | RuntimeException e) {
            // The connection is unusable for our purposes, so do not leak it
            // back into the pool still carrying an unknown context.
            closeQuietly(connection);
            throw e;
        }
        return proxy(connection);
    }

    private static void applyTenant(Connection connection, UUID tenantId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(APPLY_TENANT_SQL)) {
            statement.setString(1, tenantId == null ? NO_TENANT : tenantId.toString());
            statement.execute();
        }
    }

    private static void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException e) {
            log.warn("Failed to close a connection that could not be scoped to a tenant", e);
        }
    }

    /**
     * Wraps the connection so that returning it to the pool clears the tenant
     * first. This is defence in depth; correctness rests on the checkout path.
     */
    private Connection proxy(Connection connection) {
        return (Connection) Proxy.newProxyInstance(
                ConnectionProxy.class.getClassLoader(),
                new Class<?>[] {ConnectionProxy.class},
                new TenantScopedConnectionInvocationHandler(connection));
    }

    private static final class TenantScopedConnectionInvocationHandler implements InvocationHandler {

        private final Connection target;

        private TenantScopedConnectionInvocationHandler(Connection target) {
            this.target = target;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "getTargetConnection" -> {
                    return target;
                }
                case "equals" -> {
                    return proxy == args[0];
                }
                case "hashCode" -> {
                    return System.identityHashCode(proxy);
                }
                case "close" -> {
                    clearTenant();
                    target.close();
                    return null;
                }
                default -> {
                    // Fall through to the delegate.
                }
            }
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getTargetException();
            }
        }

        private void clearTenant() {
            if (isUsable()) {
                try {
                    applyTenant(target, null);
                } catch (SQLException e) {
                    // Harmless: the next checkout sets the parameter again
                    // before any statement runs.
                    log.debug("Could not clear the tenant parameter before returning the connection", e);
                }
            }
        }

        private boolean isUsable() {
            try {
                return !target.isClosed();
            } catch (SQLException e) {
                return false;
            }
        }
    }
}
