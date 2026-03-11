package com.pickupsports.auth;

import com.pickupsports.auth.service.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private static final String TEST_SECRET = "test-secret-for-unit-tests";
    private final JwtService jwtService = new JwtService(TEST_SECRET);

    private SecretKey deriveKey(String secret) {
        byte[] keyBytes = Arrays.copyOf(secret.getBytes(StandardCharsets.UTF_8), 32);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    @Test
    void generateToken_containsCorrectSubjectAndEmailClaims() {
        UUID userId = UUID.randomUUID();
        String email = "user@example.com";

        String jwt = jwtService.generateToken(userId, email);

        Claims claims = Jwts.parser()
            .verifyWith(deriveKey(TEST_SECRET))
            .build()
            .parseSignedClaims(jwt)
            .getPayload();

        assertThat(claims.getSubject()).isEqualTo(userId.toString());
        assertThat(claims.get("email", String.class)).isEqualTo(email);
    }

    @Test
    void generateToken_isNotExpired() {
        UUID userId = UUID.randomUUID();

        String jwt = jwtService.generateToken(userId, "user@example.com");

        Claims claims = Jwts.parser()
            .verifyWith(deriveKey(TEST_SECRET))
            .build()
            .parseSignedClaims(jwt)
            .getPayload();

        assertThat(claims.getExpiration()).isInTheFuture();
    }
}
