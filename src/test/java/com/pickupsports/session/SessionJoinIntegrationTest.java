package com.pickupsports.session;

import com.pickupsports.PostgresTestConfiguration;
import com.pickupsports.auth.service.JwtService;
import com.pickupsports.auth.service.RateLimiterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class SessionJoinIntegrationTest {

    @Autowired
    WebApplicationContext webApplicationContext;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JwtService jwtService;

    @MockitoBean
    RateLimiterService rateLimiterService;

    MockMvc mockMvc;

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        when(rateLimiterService.tryConsumeRequestLogin(any())).thenReturn(true);
        when(rateLimiterService.tryConsumeGuestJoin(any())).thenReturn(true);
    }

    // ── POST /sessions/{id}/join ─────────────────────────────────────────────

    @Test
    void join_activeSession_spotsAvailable_returnsJoined() throws Exception {
        UUID hostId = insertUser("host-join1@example.com", "Host");
        UUID sessionId = insertSession(hostId, "active", 10);
        UUID userId = insertUser("user-join1@example.com", "User");
        String token = jwtService.generateToken(userId, "user-join1@example.com");

        mockMvc.perform(post("/api/v1/sessions/{id}/join", sessionId)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("joined"));

        // M3: verify row actually persisted in DB
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM session_participants WHERE session_id = ? AND user_id = ? AND status = 'joined'",
            Integer.class, sessionId, userId);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void join_activeSession_full_returnsWaitlist() throws Exception {
        UUID hostId = insertUser("host-join2@example.com", "Host");
        UUID sessionId = insertSession(hostId, "active", 1);
        // Fill the single spot with the host
        insertParticipant(sessionId, hostId, "joined");

        UUID userId = insertUser("user-join2@example.com", "User");
        String token = jwtService.generateToken(userId, "user-join2@example.com");

        mockMvc.perform(post("/api/v1/sessions/{id}/join", sessionId)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("waitlist"));
    }

    @Test
    void join_alreadyParticipant_returns400() throws Exception {
        UUID hostId = insertUser("host-join3@example.com", "Host");
        UUID sessionId = insertSession(hostId, "active", 10);
        UUID userId = insertUser("user-join3@example.com", "User");
        insertParticipant(sessionId, userId, "joined");

        String token = jwtService.generateToken(userId, "user-join3@example.com");

        mockMvc.perform(post("/api/v1/sessions/{id}/join", sessionId)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isBadRequest());
    }

    @Test
    void join_cancelledSession_returns400() throws Exception {
        UUID hostId = insertUser("host-join4@example.com", "Host");
        UUID sessionId = insertSession(hostId, "cancelled", 10);
        UUID userId = insertUser("user-join4@example.com", "User");
        String token = jwtService.generateToken(userId, "user-join4@example.com");

        mockMvc.perform(post("/api/v1/sessions/{id}/join", sessionId)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isBadRequest());
    }

    @Test
    void join_completedSession_returns400() throws Exception {
        // M2: AC #4 covers "cancelled or completed" — verify completed is also rejected
        UUID hostId = insertUser("host-join4b@example.com", "Host");
        UUID sessionId = insertSession(hostId, "completed", 10);
        UUID userId = insertUser("user-join4b@example.com", "User");
        String token = jwtService.generateToken(userId, "user-join4b@example.com");

        mockMvc.perform(post("/api/v1/sessions/{id}/join", sessionId)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isBadRequest());
    }

    @Test
    void join_alreadyWaitlisted_returns400() throws Exception {
        // M1: AC #3 covers "status = joined or waitlist" — verify waitlisted user is also rejected
        UUID hostId = insertUser("host-join3b@example.com", "Host");
        UUID sessionId = insertSession(hostId, "active", 10);
        UUID userId = insertUser("user-join3b@example.com", "User");
        insertParticipant(sessionId, userId, "waitlist");

        String token = jwtService.generateToken(userId, "user-join3b@example.com");

        mockMvc.perform(post("/api/v1/sessions/{id}/join", sessionId)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isBadRequest());
    }

    @Test
    void join_noAuth_returns401() throws Exception {
        UUID hostId = insertUser("host-join5@example.com", "Host");
        UUID sessionId = insertSession(hostId, "active", 10);

        mockMvc.perform(post("/api/v1/sessions/{id}/join", sessionId))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void join_invalidToken_returns401() throws Exception {
        UUID hostId = insertUser("host-join6@example.com", "Host");
        UUID sessionId = insertSession(hostId, "active", 10);

        mockMvc.perform(post("/api/v1/sessions/{id}/join", sessionId)
                .header("Authorization", "Bearer invalid-token"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void join_sessionNotFound_returns404() throws Exception {
        UUID userId = insertUser("user-join7@example.com", "User");
        String token = jwtService.generateToken(userId, "user-join7@example.com");

        mockMvc.perform(post("/api/v1/sessions/{id}/join", UUID.randomUUID())
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isNotFound());
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private UUID insertUser(String email, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, name) VALUES (?, ?, ?)", id, email, name);
        return id;
    }

    private UUID insertSession(UUID hostUserId, String status, int capacity) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update(
            """
            INSERT INTO sessions
                (id, sport, title, status, start_time, end_time, capacity,
                 host_user_id, location, location_name)
            VALUES (?, 'football', 'Test Session', ?, ?, ?, ?,
                    ?, ST_SetSRID(ST_MakePoint(4.4, 51.2), 4326), 'Test Location')
            """,
            id, status,
            Timestamp.from(now.plus(1, ChronoUnit.HOURS)),
            Timestamp.from(now.plus(2, ChronoUnit.HOURS)),
            capacity,
            hostUserId
        );
        return id;
    }

    private void insertParticipant(UUID sessionId, UUID userId, String status) {
        jdbc.update(
            """
            INSERT INTO session_participants
                (id, session_id, user_id, guest_name, guest_token, status, joined_at)
            VALUES (?, ?, ?, NULL, NULL, ?, NOW())
            """,
            UUID.randomUUID(), sessionId, userId, status
        );
    }
}
