package dev.glosa.core.auth;

import dev.glosa.core.error.DuplicateResourceException;
import dev.glosa.core.tenant.Tenant;
import dev.glosa.core.tenant.TenantContext;
import dev.glosa.core.tenant.TenantRepository;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Registration and sign-in.
 *
 * <p>These are the only operations that run before a tenant context exists, and
 * the only ones allowed to resolve a tenant from something the caller supplied.
 * Everything downstream reads the tenant from a signature instead.
 */
@Service
public class AuthenticationService {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationService.class);

    /**
     * A valid bcrypt hash of a value nobody knows, verified against when no user
     * matches. Without it, a missing account would answer noticeably faster than
     * a wrong password, and that difference is enough to enumerate who has an
     * account here.
     */
    private static final String DUMMY_HASH =
            "{bcrypt}$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final TenantRepository tenants;
    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenIssuer tokens;
    private final TransactionTemplate transactions;

    public AuthenticationService(TenantRepository tenants, AppUserRepository users,
            PasswordEncoder passwordEncoder, AccessTokenIssuer tokens,
            TransactionTemplate transactions) {
        this.tenants = tenants;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.transactions = transactions;
    }

    /**
     * Creates a tenant along with its first administrator.
     *
     * <p>The tenant identifier is generated here, before anything is written,
     * because the context has to be bound for the whole transaction: the user row
     * is inserted under a policy that compares against that id, and the tenant is
     * carried on the connection from the moment it is checked out. Binding it
     * halfway through would come too late.
     */
    public Registered register(String slug, String organisationName, String email, String password) {
        String normalisedSlug = slug.trim().toLowerCase();
        String normalisedEmail = normaliseEmail(email);

        if (tenants.existsBySlug(normalisedSlug)) {
            throw new DuplicateResourceException("The handle '" + normalisedSlug + "' is taken");
        }

        UUID tenantId = UUID.randomUUID();
        return TenantContext.callWith(tenantId, () -> transactions.execute(status -> {
            tenants.save(new Tenant(tenantId, normalisedSlug, organisationName.trim()));
            AppUser admin = users.save(new AppUser(
                    tenantId, normalisedEmail, passwordEncoder.encode(password), Role.ADMIN));
            log.info("Registered tenant {} with administrator {}", normalisedSlug, admin.getId());
            return new Registered(tenantId, normalisedSlug, admin.getId());
        }));
    }

    /**
     * Exchanges credentials for an access token.
     *
     * <p>Every rejection looks the same from outside. Distinguishing "no such
     * tenant" from "no such user" from "wrong password" would turn the endpoint
     * into a directory of who exists here.
     */
    public AccessToken login(String tenantSlug, String email, String password) {
        String normalisedEmail = normaliseEmail(email);

        Optional<Tenant> tenant = tenants.findBySlug(tenantSlug.trim().toLowerCase());
        if (tenant.isEmpty()) {
            // Still spend the time a real verification would take.
            passwordEncoder.matches(password, DUMMY_HASH);
            throw new InvalidCredentialsException();
        }

        UUID tenantId = tenant.get().getId();
        return TenantContext.callWith(tenantId, () -> {
            Optional<AppUser> user = users.findByEmail(normalisedEmail);
            String hash = user.map(AppUser::getPasswordHash).orElse(DUMMY_HASH);

            if (!passwordEncoder.matches(password, hash) || user.isEmpty()) {
                log.info("Rejected sign-in for tenant {}", tenantSlug);
                throw new InvalidCredentialsException();
            }

            AppUser found = user.get();
            return tokens.issue(found.getId(), tenantId, found.getRole());
        });
    }

    /** Adds a user to the caller's own tenant. */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public AppUser addUser(String email, String password, Role role) {
        UUID tenantId = TenantContext.requireTenantId();
        String normalisedEmail = normaliseEmail(email);

        if (users.existsByEmail(normalisedEmail)) {
            throw new DuplicateResourceException("A user with that email already exists");
        }

        return users.save(new AppUser(
                tenantId, normalisedEmail, passwordEncoder.encode(password), role));
    }

    /** A check constraint requires lowercase, and addresses are not case sensitive. */
    private static String normaliseEmail(String email) {
        return email.trim().toLowerCase();
    }

    /** What registration produced. */
    public record Registered(UUID tenantId, String slug, UUID administratorId) {
    }
}
