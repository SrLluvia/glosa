package dev.glosa.core.auth;

import static dev.glosa.core.auth.AccessTokenIssuer.ROLE_CLAIM;
import static dev.glosa.core.auth.AccessTokenIssuer.TENANT_CLAIM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;

/**
 * The access token is the only thing standing between a caller and another
 * tenant's data, so these tests check both that a good token carries what it
 * should and that a bad one is refused.
 */
@DisplayName("Access token")
class AccessTokenIssuerTest {

    private static final String SECRET = "a-test-secret-long-enough-for-hmac-sha256";
    private static final String OTHER_SECRET = "a-different-secret-also-long-enough-here";
    private static final String ISSUER = "glosa";
    private static final Duration TTL = Duration.ofHours(1);

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final JwtConfiguration configuration = new JwtConfiguration();
    /**
     * Real time, because the decoder validates expiry against the system clock
     * and only the issuing side takes an injected one. Truncated to whole
     * seconds, which is the precision a JWT expiry claim carries.
     */
    private final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    @Test
    @DisplayName("carries the user, tenant and role, and verifies against our own decoder")
    void issuesAVerifiableTokenCarryingTenantAndRole() {
        AccessToken token = issuerWith(properties(SECRET, ISSUER), now).issue(USER_ID, TENANT_ID, Role.EDITOR);

        Jwt decoded = decoderWith(properties(SECRET, ISSUER)).decode(token.value());

        assertThat(decoded.getSubject()).isEqualTo(USER_ID.toString());
        assertThat(decoded.getClaimAsString(TENANT_CLAIM)).isEqualTo(TENANT_ID.toString());
        assertThat(decoded.getClaimAsString(ROLE_CLAIM)).isEqualTo(Role.EDITOR.name());
        assertThat(decoded.getClaimAsString(JwtClaimNames.ISS)).isEqualTo(ISSUER);
        assertThat(token.expiresAt()).isEqualTo(now.plus(TTL));
    }

    @Test
    @DisplayName("is rejected once it has expired")
    void rejectsAnExpiredToken() {
        // Issued far enough in the past that the default clock skew allowance
        // cannot rescue it.
        Instant longAgo = now.minus(Duration.ofHours(3));
        AccessToken expired = issuerWith(properties(SECRET, ISSUER), longAgo)
                .issue(USER_ID, TENANT_ID, Role.VIEWER);

        JwtDecoder decoder = decoderWith(properties(SECRET, ISSUER));

        assertThatThrownBy(() -> decoder.decode(expired.value()))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    @DisplayName("is rejected when signed with a different secret")
    void rejectsATokenSignedWithAnotherSecret() {
        AccessToken forged = issuerWith(properties(OTHER_SECRET, ISSUER), now)
                .issue(USER_ID, TENANT_ID, Role.ADMIN);

        JwtDecoder decoder = decoderWith(properties(SECRET, ISSUER));

        assertThatThrownBy(() -> decoder.decode(forged.value()))
                .isInstanceOf(org.springframework.security.oauth2.jwt.JwtException.class);
    }

    @Test
    @DisplayName("is rejected when it comes from another issuer")
    void rejectsATokenFromAnotherIssuer() {
        AccessToken foreign = issuerWith(properties(SECRET, "somebody-else"), now)
                .issue(USER_ID, TENANT_ID, Role.ADMIN);

        JwtDecoder decoder = decoderWith(properties(SECRET, ISSUER));

        assertThatThrownBy(() -> decoder.decode(foreign.value()))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    @DisplayName("never exposes its value through toString")
    void keepsTheTokenValueOutOfStringForm() {
        AccessToken token = issuerWith(properties(SECRET, ISSUER), now).issue(USER_ID, TENANT_ID, Role.VIEWER);

        assertThat(token).hasToString("AccessToken[expiresAt=" + token.expiresAt() + ", value=REDACTED]");
        assertThat(token.toString()).doesNotContain(token.value());
    }

    @Test
    @DisplayName("refuses a secret too short to key HMAC-SHA256")
    void refusesAShortSecret() {
        assertThatThrownBy(() -> properties("too-short", ISSUER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 32 characters");
    }

    private static JwtProperties properties(String secret, String issuer) {
        return new JwtProperties(secret, issuer, TTL);
    }

    private AccessTokenIssuer issuerWith(JwtProperties properties, Instant at) {
        return new AccessTokenIssuer(
                configuration.jwtEncoder(properties), properties, Clock.fixed(at, ZoneOffset.UTC));
    }

    private JwtDecoder decoderWith(JwtProperties properties) {
        return configuration.jwtDecoder(properties);
    }
}
