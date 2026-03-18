package com.pickupsports.chat.repository;

import com.pickupsports.chat.domain.ChatMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

@Repository
public class ChatMessageRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<ChatMessage> MESSAGE_MAPPER = (rs, i) -> new ChatMessage(
        rs.getObject("id", UUID.class),
        rs.getObject("session_id", UUID.class),
        rs.getObject("user_id", UUID.class),
        rs.getString("author_name"),
        rs.getString("content"),
        rs.getTimestamp("sent_at").toInstant()
    );

    private static final String FIND_BY_SESSION_SQL = """
        SELECT sm.id, sm.session_id, sm.user_id,
               COALESCE(u.name, sm.guest_name) AS author_name,
               sm.content, sm.sent_at
        FROM session_messages sm
        LEFT JOIN users u ON sm.user_id = u.id
        WHERE sm.session_id = ?
        ORDER BY sm.sent_at ASC, sm.id ASC
        LIMIT ? OFFSET ?
        """;

    private static final String COUNT_BY_SESSION_SQL =
        "SELECT COUNT(*) FROM session_messages WHERE session_id = ?";

    private static final String INSERT_SQL = """
        INSERT INTO session_messages (id, session_id, user_id, guest_name, content, sent_at)
        VALUES (?, ?, ?, ?, ?, ?)
        """;

    public ChatMessageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ChatMessage save(ChatMessage message) {
        jdbc.update(INSERT_SQL,
            message.id(),
            message.sessionId(),
            message.userId(),
            message.userId() != null ? null : message.authorName(),  // guest_name only for guests
            message.content(),
            Timestamp.from(message.sentAt())
        );
        return message;
    }

    public List<ChatMessage> findBySessionId(UUID sessionId, int page, int size) {
        return jdbc.query(FIND_BY_SESSION_SQL, MESSAGE_MAPPER, sessionId, size, (long) page * size);
    }

    public int countBySessionId(UUID sessionId) {
        Integer count = jdbc.queryForObject(COUNT_BY_SESSION_SQL, Integer.class, sessionId);
        return count != null ? count : 0;
    }

    public int deleteForSessionsEndedBefore(java.time.Instant cutoff) {
        return jdbc.update("""
            DELETE FROM session_messages
            WHERE session_id IN (
                SELECT id FROM sessions WHERE end_time < ?
            )
            """, Timestamp.from(cutoff));
    }
}
