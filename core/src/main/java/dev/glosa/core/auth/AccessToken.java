package dev.glosa.core.auth;

import java.time.Instant;

/**
 * An issued access token and when it stops being valid.
 *
 * <p>The expiry is returned alongside the token so a client can refresh before
 * it lapses rather than discovering the fact through a failed request. It is a
 * convenience, not a source of truth: the authoritative expiry is the signed
 * claim inside the token.
 */
public record AccessToken(String value, Instant expiresAt) {

    @Override
    public String toString() {
        // Never let a token reach a log through an accidental string
        // interpolation of the surrounding object.
        return "AccessToken[expiresAt=" + expiresAt + ", value=REDACTED]";
    }
}
