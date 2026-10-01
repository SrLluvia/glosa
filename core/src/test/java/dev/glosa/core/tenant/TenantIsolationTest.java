package dev.glosa.core.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.zaxxer.hikari.HikariDataSource;
import dev.glosa.core.support.PostgresIntegrationTest;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves that tenant isolation holds in the database rather than in our queries.
 *
 * <p>Every query here deliberately omits a tenant filter. If the policies were
 * missing or the context were not reaching the connection, these tests would see
 * the other tenant's rows and fail.
 */
@SpringBootTest
@DisplayName("Tenant isolation")
class TenantIsolationTest extends PostgresIntegrationTest {

    private static final UUID ACME_ID = UUID.fromString("00000000-0000-0000-0000-00000000aaaa");
    private static final UUID GLOBEX_ID = UUID.fromString("00000000-0000-0000-0000-00000000bbbb");

    /**
     * Single connection on purpose. Reuse is what would expose a leaked tenant
     * parameter, so a pool of one is the strictest arrangement available.
     */
    private static HikariDataSource pool;

    /** Scoped view of the database, as the application sees it. */
    private static JdbcTemplate asApplication;

    /** Privileged view, used only to arrange fixtures. Superusers bypass RLS. */
    @Autowired
    private JdbcTemplate asOwner;

    @BeforeEach
    void setUp() {
        if (pool == null) {
            pool = new HikariDataSource();
            pool.setJdbcUrl(POSTGRES.getJdbcUrl());
            pool.setUsername(APP_USERNAME);
            pool.setPassword(APP_PASSWORD);
            pool.setMaximumPoolSize(1);
            DataSource tenantAware = new TenantAwareDataSource(pool);
            asApplication = new JdbcTemplate(tenantAware);
        }

        asOwner.execute("truncate table collection, app_user, tenant cascade");
        insertTenant(ACME_ID, "acme", "Acme Corp");
        insertTenant(GLOBEX_ID, "globex", "Globex");
        insertCollection(ACME_ID, "Acme handbook");
        insertCollection(GLOBEX_ID, "Globex handbook");
    }

    @AfterAll
    static void tearDown() {
        if (pool != null) {
            pool.close();
            pool = null;
        }
    }

    @Test
    @DisplayName("a bound tenant sees only its own rows, even without a tenant filter")
    void readsOnlyRowsOfTheBoundTenant() {
        List<String> acmeNames = TenantContext.callWith(ACME_ID, this::selectAllCollectionNames);
        List<String> globexNames = TenantContext.callWith(GLOBEX_ID, this::selectAllCollectionNames);

        assertThat(acmeNames).containsExactly("Acme handbook");
        assertThat(globexNames).containsExactly("Globex handbook");
    }

    @Test
    @DisplayName("switching tenants on a reused connection does not carry rows across")
    void doesNotLeakAcrossConnectionReuse() {
        // The pool holds a single connection, so the second call necessarily
        // runs on the same physical connection as the first.
        TenantContext.callWith(ACME_ID, this::selectAllCollectionNames);

        List<String> namesAfterSwitch = TenantContext.callWith(GLOBEX_ID, this::selectAllCollectionNames);

        assertThat(namesAfterSwitch).containsExactly("Globex handbook");
    }

    @Test
    @DisplayName("an unscoped read sees nothing instead of everything")
    void readsNothingWhenNoTenantIsBound() {
        assertThat(selectAllCollectionNames()).isEmpty();
    }

    @Test
    @DisplayName("a write cannot place a row in another tenant")
    void rejectsWritingIntoAnotherTenant() {
        Throwable thrown = catchThrowable(() -> TenantContext.runWith(ACME_ID, () -> asApplication.update(
                "insert into collection (tenant_id, name) values (?, ?)",
                GLOBEX_ID, "Smuggled into Globex")));

        // Spring classifies the driver error as bad grammar, so the reason shows
        // up only on the cause. SQLSTATE 42501 is insufficient_privilege, which
        // is what Postgres reports for a WITH CHECK violation, and it is a more
        // stable contract to assert on than the wording of the message.
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(thrown);
        assertThat(cause)
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("row-level security policy");
        assertThat(((SQLException) cause).getSQLState()).isEqualTo("42501");

        assertThat(TenantContext.callWith(GLOBEX_ID, this::selectAllCollectionNames))
                .containsExactly("Globex handbook");
    }

    @Test
    @DisplayName("a write is attributed to the bound tenant and stays invisible to the other")
    void writesAreVisibleOnlyToTheirOwnTenant() {
        TenantContext.runWith(ACME_ID, () -> asApplication.update(
                "insert into collection (tenant_id, name) values (?, ?)", ACME_ID, "Acme runbook"));

        assertThat(TenantContext.callWith(ACME_ID, this::selectAllCollectionNames))
                .containsExactlyInAnyOrder("Acme handbook", "Acme runbook");
        assertThat(TenantContext.callWith(GLOBEX_ID, this::selectAllCollectionNames))
                .containsExactly("Globex handbook");
    }

    private List<String> selectAllCollectionNames() {
        return asApplication.queryForList("select name from collection order by name", String.class);
    }

    private void insertTenant(UUID id, String slug, String name) {
        asOwner.update("insert into tenant (id, slug, name) values (?, ?, ?)", id, slug, name);
    }

    private void insertCollection(UUID tenantId, String name) {
        asOwner.update("insert into collection (tenant_id, name) values (?, ?)", tenantId, name);
    }
}
