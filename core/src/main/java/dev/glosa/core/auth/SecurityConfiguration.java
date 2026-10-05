package dev.glosa.core.auth;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The API's security chain.
 *
 * <p>Everything is denied unless a rule says otherwise, so adding an endpoint
 * without thinking about access leaves it protected rather than open.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
// Role checks live on the use cases, so they hold for every caller rather than
// for one HTTP route.
@EnableMethodSecurity
class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder) throws Exception {
        return http
                // No cookies and no sessions, so there is no ambient credential a
                // forged cross-site request could ride on, which is what CSRF
                // protection defends against. Every call must carry its own
                // bearer token.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        // Login cannot require a token, by definition.
                        .requestMatchers(HttpMethod.POST, "/v1/auth/login").permitAll()
                        // Registration creates the first tenant and its first
                        // administrator, so there is nothing to authenticate as
                        // yet. Open registration suits a demo; a deployment
                        // would put an invite or a payment in front of it.
                        .requestMatchers(HttpMethod.POST, "/v1/tenants").permitAll()
                        // Liveness for the container orchestrator. Detail is off
                        // in configuration, so this leaks nothing about the
                        // database or its topology.
                        .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                        // The API description and its UI. These expose the shape
                        // of the API but none of its data, and the UI needs the
                        // spec before a caller has a token. Turn both off in
                        // production with springdoc.api-docs.enabled=false and
                        // springdoc.swagger-ui.enabled=false.
                        .requestMatchers(HttpMethod.GET, "/v3/api-docs", "/v3/api-docs/**",
                                "/swagger-ui.html", "/swagger-ui/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt
                                .decoder(jwtDecoder)
                                .jwtAuthenticationConverter(jwtAuthenticationConverter())))
                // Order matters: the tenant must be read from a token that has
                // already been verified, so this sits after authentication.
                .addFilterAfter(new TenantContextFilter(), BearerTokenAuthenticationFilter.class)
                .build();
    }

    /**
     * Maps the role claim onto a single authority.
     *
     * <p>The claim is checked against the known roles rather than trusted
     * verbatim. Tokens are ours and signed, but turning an arbitrary string into
     * an authority is the kind of shortcut that becomes a hole the day the
     * signing boundary moves.
     */
    private static JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            String claim = jwt.getClaimAsString(AccessTokenIssuer.ROLE_CLAIM);
            if (claim == null) {
                return List.of();
            }
            try {
                GrantedAuthority authority = new SimpleGrantedAuthority(Role.valueOf(claim).authority());
                return List.of(authority);
            } catch (IllegalArgumentException e) {
                // An unknown role grants nothing, which fails closed.
                return List.of();
            }
        });
        return converter;
    }

    /**
     * Delegating encoder, so stored hashes carry the algorithm that produced
     * them and can be migrated without invalidating every password.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
