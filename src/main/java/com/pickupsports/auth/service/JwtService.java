package com.pickupsports.auth.service;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {

    private final SecretKey key;

    public JwtService(@Value("${app.jwt-secret}") String jwtSecret) {
        // HS256 requires exactly 256 bits (32 bytes).
        // Arrays.copyOf truncates if secret > 32 bytes, zero-pads if < 32 bytes.
        byte[] keyBytes = Arrays.copyOf(jwtSecret.getBytes(StandardCharsets.UTF_8), 32);
        this.key = Keys.hmacShaKeyFor(keyBytes);
    }

    public String generateToken(UUID userId, String email) {
        Instant now = Instant.now();
        return Jwts.builder()
            .subject(userId.toString())
            .claim("email", email)
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(30, ChronoUnit.DAYS)))
            .signWith(key)   // HS256 inferred from 32-byte key
            .compact();
    }

    public UUID parseUserId(String jwt) {
        try {
            String subject = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(jwt)
                .getPayload()
                .getSubject();
            return UUID.fromString(subject);
        } catch (JwtException | IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired token");
        }
    }
}
