package dev.glosa.core.support;

import java.nio.file.Path;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Base class for tests that need a real Postgres.
 *
 * <p>The container runs the same pgvector image and the same provisioning script
 * as the development stack, so the two database roles, the default privileges
 * and the extension are exactly what production gets. Tests that assert on
 * tenant isolation depend on that: Row Level Security is bypassed by superusers,
 * so proving anything about it requires the real unprivileged role.
 *
 * <p>The container is started once for the whole suite and shared, because
 * migrating a fresh database per test class costs far more than the isolation it
 * would buy. Tests are responsible for leaving the data they create behind in a
 * state the next one tolerates.
 */
@ActiveProfiles("test")
public abstract class PostgresIntegrationTest {

    /** Owner role: holds the schema and is used by Flyway. */
    private static final String OWNER_USERNAME = "glosa_owner";
    private static final String OWNER_PASSWORD = "owner-password-for-tests";

    /** Application role: unprivileged, and what RLS is enforced against. */
    public static final String APP_USERNAME = "glosa_app";
    public static final String APP_PASSWORD = "app-password-for-tests";

    private static final String DATABASE_NAME = "glosa";

    private static final Path PROVISIONING_SCRIPT =
            Path.of("..", "docker", "postgres", "init", "01-app-role.sh").toAbsolutePath().normalize();

    @SuppressWarnings("resource") // Shared for the whole suite; stopped on JVM exit by Testcontainers.
    protected static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName(DATABASE_NAME)
                    .withUsername(OWNER_USERNAME)
                    .withPassword(OWNER_PASSWORD)
                    .withEnv("APP_DB_USER", APP_USERNAME)
                    .withEnv("APP_DB_PASSWORD", APP_PASSWORD)
                    .withCopyFileToContainer(
                            MountableFile.forHostPath(PROVISIONING_SCRIPT, 0755),
                            "/docker-entrypoint-initdb.d/01-app-role.sh");

    static {
        POSTGRES.start();
    }

    private static JdbcTemplate owner;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        // The application connects as the unprivileged role, exactly as it does
        // in a deployment. This is not a detail: the owner role is a superuser
        // and superusers bypass Row Level Security, so a context wired to it
        // would let every isolation assertion pass for the wrong reason.
        registry.add("spring.datasource.username", () -> APP_USERNAME);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    /**
     * Privileged access, for arranging fixtures only.
     *
     * <p>Built straight from the container rather than taken from the application
     * context, which is deliberately unprivileged. Because this role bypasses Row
     * Level Security it can seed rows across several tenants, which is what the
     * isolation tests need in order to have something to fail against.
     */
    protected static synchronized JdbcTemplate asOwner() {
        if (owner == null) {
            DriverManagerDataSource dataSource = new DriverManagerDataSource(
                    POSTGRES.getJdbcUrl(), OWNER_USERNAME, OWNER_PASSWORD);
            dataSource.setDriverClassName("org.postgresql.Driver");
            owner = new JdbcTemplate(dataSource);
        }
        return owner;
    }
}
