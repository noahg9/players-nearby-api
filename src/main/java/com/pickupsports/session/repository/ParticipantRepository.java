package com.pickupsports.session.repository;

import com.pickupsports.session.domain.Participant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ParticipantRepository {

    private final JdbcTemplate jdbc;

    private static final String SELECT_PARTICIPANT =
        """
        SELECT sp.id, sp.session_id, sp.user_id, sp.guest_name, sp.guest_token,
               sp.status, sp.joined_at,
               COALESCE(u.name, sp.guest_name) AS display_name
        FROM session_participants sp
        LEFT JOIN users u ON sp.user_id = u.id
        """;

    private static final RowMapper<Participant> PARTICIPANT_MAPPER = (rs, i) -> new Participant(
        rs.getObject("id", UUID.class),
        rs.getObject("session_id", UUID.class),
        rs.getObject("user_id", UUID.class),
        rs.getString("guest_name"),
        rs.getString("guest_token"),
        rs.getString("status"),
        rs.getTimestamp("joined_at").toInstant(),
        rs.getString("display_name")
    );

    public ParticipantRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(Participant participant) {
        jdbc.update(
            """
            INSERT INTO session_participants
                (id, session_id, user_id, guest_name, guest_token, status, joined_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
            participant.id(),
            participant.sessionId(),
            participant.userId(),
            participant.guestName(),
            participant.guestToken(),
            participant.status(),
            Timestamp.from(participant.joinedAt())
        );
    }

    public List<Participant> findBySessionId(UUID sessionId) {
        return jdbc.query(
            SELECT_PARTICIPANT + "WHERE sp.session_id = ? ORDER BY sp.joined_at",
            PARTICIPANT_MAPPER,
            sessionId
        );
    }

    public Optional<Participant> findBySessionIdAndUserId(UUID sessionId, UUID userId) {
        var rows = jdbc.query(
            SELECT_PARTICIPANT + "WHERE sp.session_id = ? AND sp.user_id = ?",
            PARTICIPANT_MAPPER,
            sessionId, userId
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<Participant> findBySessionIdAndGuestToken(UUID sessionId, String guestToken) {
        var rows = jdbc.query(
            SELECT_PARTICIPANT + "WHERE sp.session_id = ? AND sp.guest_token = ?",
            PARTICIPANT_MAPPER,
            sessionId, guestToken
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public int countJoined(UUID sessionId) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM session_participants WHERE session_id = ? AND status = 'joined'",
            Integer.class,
            sessionId
        );
        return count != null ? count : 0;
    }

    public Optional<Participant> findOldestWaitlisted(UUID sessionId) {
        var rows = jdbc.query(
            SELECT_PARTICIPANT +
            "WHERE sp.session_id = ? AND sp.status = 'waitlist' ORDER BY sp.joined_at ASC LIMIT 1",
            PARTICIPANT_MAPPER,
            sessionId
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public void updateStatus(UUID id, String status) {
        jdbc.update("UPDATE session_participants SET status = ? WHERE id = ?", status, id);
    }

    public void delete(UUID id) {
        jdbc.update("DELETE FROM session_participants WHERE id = ?", id);
    }
}
