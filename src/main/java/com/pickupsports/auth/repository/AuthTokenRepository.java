package com.pickupsports.auth.repository;

import com.pickupsports.auth.domain.AuthToken;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AuthTokenRepository {

    private final JdbcTemplate jdbc;

    public AuthTokenRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(AuthToken token) {
        jdbc.update(
            "INSERT INTO auth_tokens (id, token, email, expires_at) VALUES (?, ?, ?, ?)",
            token.id(), token.token(), token.email(), Timestamp.from(token.expiresAt())
        );
    }

    public Optional<AuthToken> findByToken(String token) {
        var rows = jdbc.query(
            "SELECT id, token, email, expires_at, used_at, created_at FROM auth_tokens WHERE token = ?",
            (rs, i) -> new AuthToken(
                rs.getObject("id", UUID.class),
                rs.getString("token"),
                rs.getString("email"),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("used_at") != null ? rs.getTimestamp("used_at").toInstant() : null,
                rs.getTimestamp("created_at").toInstant()
            ),
            token
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * Atomically marks the token as used only if it has not been used yet.
     * Returns true if the update succeeded (token was unused), false if it was already used.
     * This prevents TOCTOU races where two concurrent requests consume the same token.
     */
    public boolean markUsedIfUnused(String token, Instant usedAt) {
        int rows = jdbc.update(
            "UPDATE auth_tokens SET used_at = ? WHERE token = ? AND used_at IS NULL",
            Timestamp.from(usedAt), token);
        return rows > 0;
    }
}
