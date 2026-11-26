package com.parking.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.util.Base64;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

class TokenServiceTest {

    private final SecretKey key = new SecretKeySpec(
            Base64.getDecoder().decode("bG9jYWwtZGV2LW9ubHktand0LXNpZ25pbmcta2V5LTAxMjM0NTY3ODk="), "HmacSHA256");

    @Test
    void issuedTokenIsValidHs256WithUserIdSubjectAndTtl() {
        TokenService tokens = new TokenService(new NimbusJwtEncoder(new ImmutableSecret<>(key)), Clock.systemUTC(),
                Duration.ofHours(12));

        Jwt jwt = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build()
                .decode(tokens.issue(42L, "alice"));

        assertThat(jwt.getSubject()).isEqualTo("42");
        assertThat(jwt.getClaimAsString("username")).isEqualTo("alice");
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofHours(12));
    }
}
