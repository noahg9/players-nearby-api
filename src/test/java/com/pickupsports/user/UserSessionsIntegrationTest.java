package com.pickupsports.user;

import com.pickupsports.PostgresTestConfiguration;
import com.pickupsports.auth.service.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class UserSessionsIntegrationTest {

    @Autowired WebApplicationContext webApplicationContext;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwtService;

    MockMvc mockMvc;

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    // ── GET /users/me/sessions ───────────────────────────────────────────────

    @Test
    void getMySessions_hosting_returnsHostedSession() throws Exception {
        UUID hostId = insertUser("Host User");
        UUID sessionId = insertSession(hostId, "active", 10);
        insertParticipant(sessionId, hostId, "joined");

        mockMvc.perform(get("/api/v1/users/me/sessions")
                .param("role", "hosting")
                .header("Authorization", bearerToken(hostId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].id").value(sessionId.toString()))
            .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void getMySessions_joined_returnsJoinedSession() throws Exception {
        UUID hostId = insertUser("Host");
        UUID joinerId = insertUser("Joiner");
        UUID sessionId = insertSession(hostId, "active", 10);
        insertParticipant(sessionId, hostId, "joined");
        insertParticipant(sessionId, joinerId, "joined");

        mockMvc.perform(get("/api/v1/users/me/sessions")
                .param("role", "joined")
                .header("Authorization", bearerToken(joinerId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].id").value(sessionId.toString()))
            .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void getMySessions_all_returnsBothRoles() throws Exception {
        UUID userId = insertUser("User");
        UUID otherId = insertUser("Other");

        // Session A: userId is host
        UUID sessionA = insertSession(userId, "active", 10);
        insertParticipant(sessionA, userId, "joined");

        // Session B: otherId is host, userId is participant
        UUID sessionB = insertSession(otherId, "active", 10);
        insertParticipant(sessionB, otherId, "joined");
        insertParticipant(sessionB, userId, "joined");

        mockMvc.perform(get("/api/v1/users/me/sessions")
                .param("role", "all")
                .param("status", "active")
                .header("Authorization", bearerToken(userId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(2))
            .andExpect(jsonPath("$.total").value(2));
    }

    @Test
    void getMySessions_hosting_excludesJoinedSession() throws Exception {
        UUID hostId = insertUser("Host");
        UUID joinerId = insertUser("Joiner");
        UUID sessionId = insertSession(hostId, "active", 10);
        insertParticipant(sessionId, hostId, "joined");
        insertParticipant(sessionId, joinerId, "joined");

        // Joiner requests role=hosting — should see nothing
        mockMvc.perform(get("/api/v1/users/me/sessions")
                .param("role", "hosting")
                .header("Authorization", bearerToken(joinerId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(0))
            .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void getMySessions_joined_excludesHostedSession() throws Exception {
        UUID hostId = insertUser("Host");
        UUID sessionId = insertSession(hostId, "active", 10);
        insertParticipant(sessionId, hostId, "joined");

        // Host requests role=joined — should see nothing (host is not "joined" role)
        mockMvc.perform(get("/api/v1/users/me/sessions")
                .param("role", "joined")
                .header("Authorization", bearerToken(hostId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(0))
            .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void getMySessions_statusActive_default_returnsOnlyActive() throws Exception {
        UUID userId = insertUser("User");
        UUID activeSession = insertSession(userId, "active", 10);
        UUID completedSession = insertSession(userId, "completed", 10);
        insertParticipant(activeSession, userId, "joined");
        insertParticipant(completedSession, userId, "joined");

        // Default status=active
        mockMvc.perform(get("/api/v1/users/me/sessions")
                .header("Authorization", bearerToken(userId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].id").value(activeSession.toString()));
    }

    @Test
    void getMySessions_statusCompleted_returnsCompleted() throws Exception {
        UUID userId = insertUser("User");
        UUID completedSession = insertSession(userId, "completed", 10);
        insertParticipant(completedSession, userId, "joined");

        mockMvc.perform(get("/api/v1/users/me/sessions")
                .param("status", "completed")
                .header("Authorization", bearerToken(userId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].id").value(completedSession.toString()))
            .andExpect(jsonPath("$.content[0].status").value("completed"));
    }

    @Test
    void getMySessions_statusAll_returnsAllStatuses() throws Exception {
        UUID userId = insertUser("User");
        UUID activeSession = insertSession(userId, "active", 10);
        UUID completedSession = insertSession(userId, "completed", 10);
        UUID cancelledSession = insertSession(userId, "cancelled", 10);
        insertParticipant(activeSession, userId, "joined");
        insertParticipant(completedSession, userId, "joined");
        insertParticipant(cancelledSession, userId, "joined");

        mockMvc.perform(get("/api/v1/users/me/sessions")
                .param("status", "all")
                .header("Authorization", bearerToken(userId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(3))
            .andExpect(jsonPath("$.total").value(3));
    }

    @Test
    void getMySessions_leftSession_notReturned() throws Exception {
        UUID hostId = insertUser("Host");
        UUID userId = insertUser("User");
        UUID sessionId = insertSession(hostId, "active", 10);
        insertParticipant(sessionId, hostId, "joined");
        insertParticipant(sessionId, userId, "left");  // left — should not appear

        mockMvc.perform(get("/api/v1/users/me/sessions")
                .param("status", "all")
                .header("Authorization", bearerToken(userId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(0))
            .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void getMySessions_waitlistParticipant_isIncluded() throws Exception {
        UUID hostId = insertUser("Host");
        UUID waitlisterId = insertUser("Waitlister");
        UUID sessionId = insertSession(hostId, "active", 1); // capacity 1 — waitlist scenario
        insertParticipant(sessionId, hostId, "joined");
        insertParticipant(sessionId, waitlisterId, "waitlist");

        mockMvc.perform(get("/api/v1/users/me/sessions")
                .param("status", "active")
                .header("Authorization", bearerToken(waitlisterId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].id").value(sessionId.toString()))
            .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void getMySessions_pagination_secondPageReturnsCorrectSession() throws Exception {
        UUID userId = insertUser("User");
        UUID sessionA = insertSession(userId, "active", 10);
        UUID sessionB = insertSession(userId, "active", 10);
        insertParticipant(sessionA, userId, "joined");
        insertParticipant(sessionB, userId, "joined");

        // page=0, size=1 — should return exactly 1 result, total=2
        mockMvc.perform(get("/api/v1/users/me/sessions")
                .param("size", "1")
                .param("page", "0")
                .header("Authorization", bearerToken(userId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.total").value(2))
            .andExpect(jsonPath("$.size").value(1))
            .andExpect(jsonPath("$.page").value(0));

        // page=1, size=1 — should return the other session
        mockMvc.perform(get("/api/v1/users/me/sessions")
                .param("size", "1")
                .param("page", "1")
                .header("Authorization", bearerToken(userId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.total").value(2))
            .andExpect(jsonPath("$.page").value(1));
    }

    @Test
    void getMySessions_invalidRole_returns400() throws Exception {
        UUID userId = insertUser("User");

        mockMvc.perform(get("/api/v1/users/me/sessions")
                .param("role", "invalid")
                .header("Authorization", bearerToken(userId)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void getMySessions_invalidStatus_returns400() throws Exception {
        UUID userId = insertUser("User");

        mockMvc.perform(get("/api/v1/users/me/sessions")
                .param("status", "unknown")
                .header("Authorization", bearerToken(userId)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void getMySessions_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/users/me/sessions"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void getMySessions_invalidToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/users/me/sessions")
                .header("Authorization", "Bearer not-a-valid-token"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void getMySessions_responseShape_hasAllFields() throws Exception {
        UUID userId = insertUser("User");
        UUID sessionId = insertSession(userId, "active", 10);
        insertParticipant(sessionId, userId, "joined");

        mockMvc.perform(get("/api/v1/users/me/sessions")
                .header("Authorization", bearerToken(userId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].id").exists())
            .andExpect(jsonPath("$.content[0].sport").value("football"))
            .andExpect(jsonPath("$.content[0].title").exists())
            .andExpect(jsonPath("$.content[0].locationName").exists())
            .andExpect(jsonPath("$.content[0].location.lat").exists())
            .andExpect(jsonPath("$.content[0].location.lng").exists())
            .andExpect(jsonPath("$.content[0].startTime").exists())
            .andExpect(jsonPath("$.content[0].endTime").exists())
            .andExpect(jsonPath("$.content[0].capacity").value(10))
            .andExpect(jsonPath("$.content[0].participantCount").value(1))
            .andExpect(jsonPath("$.content[0].spotsLeft").value(9))
            .andExpect(jsonPath("$.content[0].status").value("active"))
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(20));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private UUID insertUser(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, name) VALUES (?, ?, ?)",
            id, "test-" + id + "@example.com", name);
        return id;
    }

    private UUID insertSession(UUID hostUserId, String status, int capacity) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
            INSERT INTO sessions
                (id, sport, title, status, start_time, end_time, capacity,
                 host_user_id, location, location_name)
            VALUES (?, 'football', 'Test Session', ?, ?, ?, ?,
                    ?, ST_SetSRID(ST_MakePoint(4.4, 51.2), 4326), 'Test Location')
            """,
            id, status,
            Timestamp.from(now.plus(1, ChronoUnit.HOURS)),
            Timestamp.from(now.plus(2, ChronoUnit.HOURS)),
            capacity, hostUserId
        );
        return id;
    }

    private void insertParticipant(UUID sessionId, UUID userId, String status) {
        jdbc.update("""
            INSERT INTO session_participants
                (id, session_id, user_id, status, joined_at)
            VALUES (?, ?, ?, ?, NOW())
            """,
            UUID.randomUUID(), sessionId, userId, status
        );
    }

    private String bearerToken(UUID userId) {
        return "Bearer " + jwtService.generateToken(userId, "test@example.com");
    }
}
