package dev.glosa.core.auth;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Users of the current tenant.
 *
 * <p>Row Level Security narrows these queries, so the login flow must bind the
 * tenant resolved from the submitted slug before it looks a user up.
 */
public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    Optional<AppUser> findByEmail(String email);

    boolean existsByEmail(String email);
}
