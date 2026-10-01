package dev.glosa.probe;

import dev.glosa.core.tenant.TenantContext;
import java.util.List;
import java.util.Comparator;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reports what the security chain produced for the current request, so tests can
 * assert on it before any real endpoint exists.
 *
 * <p>It lives outside the {@code dev.glosa.core} package on purpose: component
 * scanning is rooted there, so this is invisible unless a test imports it
 * explicitly. A probe endpoint that could be picked up by accident has no place
 * in an application that serves tenant data.
 */
@RestController
public class TenantProbeController {

    public record Probe(String tenant, List<String> authorities) {
    }

    @GetMapping("/test/probe")
    Probe probe(Authentication authentication) {
        List<String> authorities = authentication == null
                ? List.of()
                : authentication.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .sorted(Comparator.naturalOrder())
                        .toList();

        return new Probe(
                TenantContext.currentTenantId().map(Object::toString).orElse(null),
                authorities);
    }
}
