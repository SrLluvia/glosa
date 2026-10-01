package dev.glosa.core.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/**
 * Issues the access tokens that carry a user's identity, tenant and role.
 *
 * <p>The tenant travels as a signed claim. That is the whole point: the server
 * decides which tenant a caller belongs to at login, seals it, and from then on
 * reads it back from the signature rather than from anything the client sends.
 * A caller cannot move between tenants without forging the token.
 */
@Component
public class AccessTokenIssuer {

    /** Tenant the token is scoped to. */
    public static final String TENANT_CLAIM = "tenant";

    /** Role within that tenant. */
    public static final String ROLE_CLAIM = "role";

    private final JwtEncoder encoder;
    private final JwtProperties properties;
    private final Clock clock;

    public AccessTokenIssuer(JwtEncoder encoder, JwtProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
    }

    public AccessToken issue(UUID userId, UUID tenantId, Role role) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(properties.accessTokenTtl());

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(userId.toString())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim(TENANT_CLAIM, tenantId.toString())
                .claim(ROLE_CLAIM, role.name())
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();

        return new AccessToken(value, expiresAt);
    }
}
