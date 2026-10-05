package dev.glosa.core.tenant;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Lists the tenants that exist.
 *
 * <p>The only place in the application that looks across tenants, and it reads
 * nothing but identifiers. The tenant table is deliberately outside Row Level
 * Security, so this works with no context bound, and it is what lets background
 * work visit each tenant in turn rather than being granted a privilege that
 * would let it see all of them at once.
 */
@Component
public class TenantDirectory {

    private final JdbcTemplate jdbc;

    public TenantDirectory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<UUID> allTenantIds() {
        return jdbc.queryForList("select id from tenant order by created_at", UUID.class);
    }
}
