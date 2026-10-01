package dev.glosa.core.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.Clock;
import javax.crypto.SecretKey;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Signing and verification of the service's own access tokens.
 *
 * <p>A symmetric key is enough here because the same service issues and verifies
 * the tokens; no third party needs to check a signature. Swapping in an external
 * identity provider later means replacing the decoder with one that fetches
 * public keys, and dropping the issuer.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtProperties.class)
class JwtConfiguration {

    @Bean
    JwtEncoder jwtEncoder(JwtProperties properties) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(properties.signingKey()));
    }

    @Bean
    JwtDecoder jwtDecoder(JwtProperties properties) {
        SecretKey key = properties.signingKey();
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key)
                // Pinning the algorithm stops a token from talking the verifier
                // into using a weaker one than the issuer chose.
                .macAlgorithm(MacAlgorithm.HS256)
                .build();

        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(),
                new JwtIssuerValidator(properties.issuer()));
        decoder.setJwtValidator(validator);

        return decoder;
    }

    /**
     * Exposed as a bean so expiry behaviour can be tested without waiting for
     * real time to pass.
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
