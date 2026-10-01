package dev.glosa.core.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Settings for the access tokens this service issues and verifies.
 *
 * <p>The secret never has a default. A signing key that ships with the source is
 * no key at all, so the service refuses to start without one being supplied by
 * the environment.
 */
@Validated
@ConfigurationProperties("glosa.security.jwt")
public record JwtProperties(
        @NotBlank String secret,
        @NotBlank String issuer,
        @NotNull Duration accessTokenTtl) {

    /**
     * HS256 derives a 256-bit key, so a shorter secret would be stretched and
     * weaken the signature. Generate one with {@code openssl rand -hex 32}.
     */
    private static final int MINIMUM_SECRET_LENGTH = 32;

    public JwtProperties {
        if (secret != null && secret.length() < MINIMUM_SECRET_LENGTH) {
            throw new IllegalArgumentException(
                    "glosa.security.jwt.secret must be at least " + MINIMUM_SECRET_LENGTH + " characters");
        }
        if (accessTokenTtl != null && (accessTokenTtl.isNegative() || accessTokenTtl.isZero())) {
            throw new IllegalArgumentException("glosa.security.jwt.access-token-ttl must be positive");
        }
    }

    /** Signing key, derived from the configured secret. */
    public SecretKey signingKey() {
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
