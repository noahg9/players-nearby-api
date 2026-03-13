package com.pickupsports.session.repository;

import com.pickupsports.session.domain.Session;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
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

    public Session save(UUID id, String sport, String title, String notes,
                        Instant startTime, Instant endTime, int capacity,
                        UUID hostUserId, double lat, double lng, String locationName) {
        jdbc.update(
            """
            INSERT INTO sessions
                (id, sport, title, notes, status, start_time, end_time, capacity,
                 host_user_id, location, location_name)
            VALUES (?, ?, ?, ?, 'active', ?, ?, ?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326), ?)
            """,
            id, sport, title, notes,
            Timestamp.from(startTime), Timestamp.from(endTime),
            capacity, hostUserId,
            lng, lat,  // ST_MakePoint(lng, lat) — longitude first
            locationName
        );
        return findById(id).orElseThrow();
    }

    public Session update(UUID id, String title, String notes,
                          Instant startTime, Instant endTime, Integer capacity) {
        jdbc.update(
            """
            UPDATE sessions
            SET title = COALESCE(?, title),
                notes = COALESCE(?, notes),
                start_time = COALESCE(?, start_time),
                end_time = COALESCE(?, end_time),
                capacity = COALESCE(?, capacity)
            WHERE id = ?
            """,
            title, notes,
            startTime != null ? Timestamp.from(startTime) : null,
            endTime != null ? Timestamp.from(endTime) : null,
            capacity, id
        );
        return findById(id).orElseThrow();
    }

    public void cancel(UUID id) {
        jdbc.update("UPDATE sessions SET status = 'cancelled' WHERE id = ?", id);
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
