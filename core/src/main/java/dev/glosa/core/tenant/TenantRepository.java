package dev.glosa.core.tenant;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Tenants.
 *
 * <p>The only repository not narrowed by Row Level Security, because login has
 * to resolve a tenant from its slug before any context can exist. Rows hold no
 * customer data, and nothing here is ever exposed as a listing.
 */
public interface TenantRepository extends JpaRepository<Tenant, UUID> {

    Optional<Tenant> findBySlug(String slug);

    boolean existsBySlug(String slug);
}
