package com.pickupsports.user.repository;

import com.pickupsports.user.domain.User;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

@Repository
public class UserRepository {

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<User> findById(UUID id) {
        var rows = jdbc.query(
            "SELECT id, email, name, bio, created_at FROM users WHERE id = ?",
            (rs, i) -> new User(
                rs.getObject("id", UUID.class),
                rs.getString("email"),
                rs.getString("name"),
                rs.getString("bio"),
                rs.getTimestamp("created_at").toInstant()
            ),
            id
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<User> findByEmail(String email) {
        var rows = jdbc.query(
            "SELECT id, email, name, bio, created_at FROM users WHERE email = ?",
            (rs, i) -> new User(
                rs.getObject("id", UUID.class),
                rs.getString("email"),
                rs.getString("name"),
                rs.getString("bio"),
                rs.getTimestamp("created_at").toInstant()
            ),
            email
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public User save(User user) {
        jdbc.update(
            "INSERT INTO users (id, email, name) VALUES (?, ?, ?)",
            user.id(), user.email(), user.name()
        );
        return user;
    }

    public void deleteById(UUID id) {
        jdbc.update("DELETE FROM users WHERE id = ?", id);
    }

    public User update(UUID id, String name, String bio) {
        jdbc.update(
            "UPDATE users SET name = COALESCE(?, name), bio = COALESCE(?, bio) WHERE id = ?",
            name, bio, id
        );
        return findById(id).orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }
}
