package com.pickupsports.session.repository;

import com.pickupsports.session.domain.Session;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class SessionRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<Session> SESSION_MAPPER = (rs, i) -> new Session(
        rs.getObject("id", UUID.class),
        rs.getString("sport"),
        rs.getString("title"),
        rs.getString("notes"),
        rs.getString("status"),
        rs.getString("visibility"),
        rs.getTimestamp("start_time").toInstant(),
        rs.getTimestamp("end_time").toInstant(),
        rs.getInt("capacity"),
        rs.getObject("host_user_id", UUID.class),
        rs.getString("location_name"),
        rs.getDouble("lat"),
        rs.getDouble("lng"),
        rs.getTimestamp("created_at").toInstant()
    );

    public SessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Session> findById(UUID id) {
        var rows = jdbc.query(
            """
            SELECT id, sport, title, notes, status, visibility,
                   ST_Y(location) AS lat, ST_X(location) AS lng,
                   start_time, end_time, capacity, host_user_id,
                   location_name, created_at
            FROM sessions
            WHERE id = ?
            """,
            SESSION_MAPPER,
            id
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}
