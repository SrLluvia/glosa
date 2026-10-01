package dev.glosa.core.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import dev.glosa.core.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds the tenant from the verified access token to the current request.
 *
 * <p>This runs after authentication, so the tenant comes from a signature we
 * checked, never from a header, a path segment or a body field the caller
 * controls. Requests that are not authenticated leave the context unset, and an
 * unset context matches no rows.
 *
 * <p>The context is always cleared on the way out. Request threads are pooled,
 * so a tenant left bound would be inherited by whoever gets the thread next.
 *
 * <p>Deliberately not a {@code @Component}. Being a bean would make Boot register
 * it in the servlet chain with no defined order relative to the security chain,
 * and a run before authentication would read an unverified tenant. It is
 * registered explicitly, after the bearer token filter, by the security
 * configuration.
 */
public class TenantContextFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TenantContextFilter.class);

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            tenantOf(SecurityContextHolder.getContext().getAuthentication())
                    .ifPresent(TenantContext::bind);
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private Optional<UUID> tenantOf(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return Optional.empty();
        }

        String claim = jwt.getClaimAsString(AccessTokenIssuer.TENANT_CLAIM);
        if (claim == null) {
            // A token we signed always carries the claim, so its absence means
            // the token predates a change in its shape. Refusing to guess is
            // safer than falling back to some default tenant.
            log.warn("Authenticated token carries no tenant claim; request will run unscoped");
            return Optional.empty();
        }

        try {
            return Optional.of(UUID.fromString(claim));
        } catch (IllegalArgumentException e) {
            log.warn("Authenticated token carries a malformed tenant claim; request will run unscoped");
            return Optional.empty();
        }
    }
}
