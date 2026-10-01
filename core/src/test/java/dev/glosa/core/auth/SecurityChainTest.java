package dev.glosa.core.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.glosa.core.support.PostgresIntegrationTest;
import dev.glosa.probe.TenantProbeController;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Checks what the security chain does with a request, which is the part that
 * decides whether tenant isolation is even reachable.
 *
 * <p>Tokens here are minted by the real issuer, so these tests also prove the
 * issuing and verifying halves agree on claim names and signing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TenantProbeController.class)
@DisplayName("Security chain")
class SecurityChainTest extends PostgresIntegrationTest {

    private static final UUID USER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID TENANT_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccessTokenIssuer issuer;

    /** Used to forge claim shapes the issuer deliberately will not produce. */
    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private JwtProperties jwtProperties;

    @Test
    @DisplayName("refuses a request with no token")
    void refusesAnUnauthenticatedRequest() throws Exception {
        mockMvc.perform(get("/test/probe"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("refuses a token it did not sign")
    void refusesAForgedToken() throws Exception {
        mockMvc.perform(get("/test/probe").header("Authorization", "Bearer not.a.real.token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("binds the tenant from the verified token")
    void bindsTheTenantFromTheVerifiedToken() throws Exception {
        mockMvc.perform(get("/test/probe").header("Authorization", bearer(Role.EDITOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant").value(TENANT_ID.toString()));
    }

    @Test
    @DisplayName("grants the role from the claim and nothing else role-shaped")
    void grantsTheAuthorityFromTheRoleClaim() throws Exception {
        // Spring Security 7 also records how the caller authenticated, as
        // FACTOR_BEARER. Asserting the exact list keeps that visible: if a future
        // change starts handing out extra authorities, this test says so rather
        // than quietly passing. The probe sorts them, so the order is stable.
        mockMvc.perform(get("/test/probe").header("Authorization", bearer(Role.VIEWER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorities").value(contains("FACTOR_BEARER", "ROLE_VIEWER")));
    }

    @Test
    @DisplayName("grants no role when the claim names one we do not know")
    void grantsNoRoleForAnUnknownClaim() throws Exception {
        // Fails closed: an unrecognised role must not become an authority.
        mockMvc.perform(get("/test/probe").header("Authorization", bearerWithRawRole("SUPERUSER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorities").value(contains("FACTOR_BEARER")));
    }

    @Test
    @DisplayName("leaves the tenant unbound once the request is done")
    void clearsTheTenantAfterTheRequest() throws Exception {
        mockMvc.perform(get("/test/probe").header("Authorization", bearer(Role.ADMIN)))
                .andExpect(status().isOk());

        // The filter clears the context in a finally block. Were it not to, this
        // thread would hand the tenant to whichever request came next.
        assertThat(dev.glosa.core.tenant.TenantContext.currentTenantId()).isEmpty();
    }

    @Test
    @DisplayName("creates no session, so there is no ambient credential to forge against")
    void createsNoSession() throws Exception {
        MvcResult result = mockMvc.perform(get("/test/probe").header("Authorization", bearer(Role.VIEWER)))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getRequest().getSession(false)).isNull();
    }

    @Test
    @DisplayName("serves health without a token and without leaking detail")
    void servesHealthWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    private String bearer(Role role) {
        return "Bearer " + issuer.issue(USER_ID, TENANT_ID, role).value();
    }

    /**
     * A properly signed token whose role claim is not one of ours. The issuer
     * takes the enum and so cannot produce this, but a token signed before a role
     * was removed could look exactly like it.
     */
    private String bearerWithRawRole(String role) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                .subject(USER_ID.toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claim(AccessTokenIssuer.TENANT_CLAIM, TENANT_ID.toString())
                .claim(AccessTokenIssuer.ROLE_CLAIM, role)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
