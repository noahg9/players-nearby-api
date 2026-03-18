package com.pickupsports.session.repository;

import com.pickupsports.session.domain.Session;
import com.pickupsports.session.domain.SessionSummary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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

    private static final RowMapper<SessionSummary> SUMMARY_MAPPER = (rs, i) -> new SessionSummary(
        rs.getObject("id", UUID.class),
        rs.getString("sport"),
        rs.getString("title"),
        rs.getString("location_name"),
        rs.getDouble("lat"),
        rs.getDouble("lng"),
        rs.getTimestamp("start_time").toInstant(),
        rs.getTimestamp("end_time").toInstant(),
        rs.getInt("capacity"),
        rs.getInt("participant_count"),
        rs.getString("status")
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
                          Instant startTime, Instant endTime, Integer capacity,
                          String sport, String locationName, Double lat, Double lng) {
        if (lat != null && lng != null) {
            jdbc.update(
                """
                UPDATE sessions
                SET title = COALESCE(?, title),
                    notes = COALESCE(?, notes),
                    start_time = COALESCE(?, start_time),
                    end_time = COALESCE(?, end_time),
                    capacity = COALESCE(?, capacity),
                    sport = COALESCE(?, sport),
                    location_name = COALESCE(?, location_name),
                    location = ST_SetSRID(ST_MakePoint(?, ?), 4326)
                WHERE id = ?
                """,
                title, notes,
                startTime != null ? Timestamp.from(startTime) : null,
                endTime != null ? Timestamp.from(endTime) : null,
                capacity, sport, locationName,
                lng, lat,  // ST_MakePoint(lng, lat) — longitude first
                id
            );
        } else {
            jdbc.update(
                """
                UPDATE sessions
                SET title = COALESCE(?, title),
                    notes = COALESCE(?, notes),
                    start_time = COALESCE(?, start_time),
                    end_time = COALESCE(?, end_time),
                    capacity = COALESCE(?, capacity),
                    sport = COALESCE(?, sport),
                    location_name = COALESCE(?, location_name)
                WHERE id = ?
                """,
                title, notes,
                startTime != null ? Timestamp.from(startTime) : null,
                endTime != null ? Timestamp.from(endTime) : null,
                capacity, sport, locationName,
                id
            );
        }
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

    public List<SessionSummary> findNearby(double lat, double lng, double radiusMeters,
                                            String sport, Instant from, Instant to,
                                            int page, int size) {
        var sql = new StringBuilder("""
            SELECT s.id, s.sport, s.title, s.location_name,
                   ST_Y(s.location) AS lat, ST_X(s.location) AS lng,
                   s.start_time, s.end_time, s.capacity, s.status,
                   COUNT(sp.id) FILTER (WHERE sp.status = 'joined') AS participant_count
            FROM sessions s
            LEFT JOIN session_participants sp ON sp.session_id = s.id
            WHERE s.status = 'active'
              AND ST_DWithin(s.location::geography, ST_MakePoint(?, ?)::geography, ?)
              AND s.start_time >= ?
            """);
        var params = new ArrayList<>();
        params.add(lng);  // ST_MakePoint(lng, lat) — longitude first
        params.add(lat);
        params.add(radiusMeters);
        params.add(Timestamp.from(from));
        if (sport != null && !sport.isBlank()) {
            sql.append("  AND s.sport = ?\n");
            params.add(sport);
        }
        if (to != null) {
            sql.append("  AND s.start_time <= ?\n");
            params.add(Timestamp.from(to));
        }
        sql.append("GROUP BY s.id\nORDER BY s.start_time ASC\nLIMIT ? OFFSET ?");
        params.add(size);
        params.add((long) page * size);

        return jdbc.query(sql.toString(), SUMMARY_MAPPER, params.toArray());
    }

    public long countNearby(double lat, double lng, double radiusMeters,
                             String sport, Instant from, Instant to) {
        var sql = new StringBuilder("""
            SELECT COUNT(*) FROM sessions s
            WHERE s.status = 'active'
              AND ST_DWithin(s.location::geography, ST_MakePoint(?, ?)::geography, ?)
              AND s.start_time >= ?
            """);
        var params = new ArrayList<>();
        params.add(lng);  // ST_MakePoint(lng, lat) — longitude first
        params.add(lat);
        params.add(radiusMeters);
        params.add(Timestamp.from(from));
        if (sport != null && !sport.isBlank()) {
            sql.append("  AND s.sport = ?\n");
            params.add(sport);
        }
        if (to != null) {
            sql.append("  AND s.start_time <= ?\n");
            params.add(Timestamp.from(to));
        }
        Long count = jdbc.queryForObject(sql.toString(), Long.class, params.toArray());
        return count != null ? count : 0L;
    }

    public List<SessionSummary> findByUserId(UUID userId, String role, String status, int page, int size) {
        var sql = new StringBuilder("""
            SELECT s.id, s.sport, s.title, s.location_name,
                   ST_Y(s.location) AS lat, ST_X(s.location) AS lng,
                   s.start_time, s.end_time, s.capacity, s.status,
                   COUNT(sp2.id) FILTER (WHERE sp2.status = 'joined') AS participant_count
            FROM sessions s
            JOIN session_participants my_sp ON my_sp.session_id = s.id
                AND my_sp.user_id = ?
                AND my_sp.status IN ('joined', 'waitlist')
            LEFT JOIN session_participants sp2 ON sp2.session_id = s.id
            WHERE 1=1
            """);
        var params = new ArrayList<>();
        params.add(userId);

        if ("hosting".equals(role)) {
            sql.append("  AND s.host_user_id = ?\n");
            params.add(userId);
        } else if ("joined".equals(role)) {
            sql.append("  AND s.host_user_id != ?\n");
            params.add(userId);
        }

        if (!"all".equals(status)) {
            sql.append("  AND s.status = ?\n");
            params.add(status);
        }

        sql.append("GROUP BY s.id\nORDER BY s.start_time ASC\nLIMIT ? OFFSET ?");
        params.add(size);
        params.add((long) page * size);

        return jdbc.query(sql.toString(), SUMMARY_MAPPER, params.toArray());
    }

    public List<Session> findActiveSessionsByHostUserId(UUID hostUserId) {
        return jdbc.query(
            """
            SELECT id, sport, title, notes, status, visibility,
                   ST_Y(location) AS lat, ST_X(location) AS lng,
                   start_time, end_time, capacity, host_user_id,
                   location_name, created_at
            FROM sessions
            WHERE host_user_id = ? AND status = 'active'
            """,
            SESSION_MAPPER,
            hostUserId);
    }

    public int markExpiredSessionsCompleted() {
        return jdbc.update(
            "UPDATE sessions SET status = 'completed' WHERE status = 'active' AND end_time < NOW()"
        );
    }

    public long countByUserId(UUID userId, String role, String status) {
        var sql = new StringBuilder("""
            SELECT COUNT(DISTINCT s.id)
            FROM sessions s
            JOIN session_participants my_sp ON my_sp.session_id = s.id
                AND my_sp.user_id = ?
                AND my_sp.status IN ('joined', 'waitlist')
            WHERE 1=1
            """);
        var params = new ArrayList<>();
        params.add(userId);

        if ("hosting".equals(role)) {
            sql.append("  AND s.host_user_id = ?\n");
            params.add(userId);
        } else if ("joined".equals(role)) {
            sql.append("  AND s.host_user_id != ?\n");
            params.add(userId);
        }

        if (!"all".equals(status)) {
            sql.append("  AND s.status = ?\n");
            params.add(status);
        }

        Long count = jdbc.queryForObject(sql.toString(), Long.class, params.toArray());
        return count != null ? count : 0L;
    }
}
