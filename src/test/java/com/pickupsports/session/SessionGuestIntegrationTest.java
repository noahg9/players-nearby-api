package com.pickupsports.session;

import com.pickupsports.PostgresTestConfiguration;
import com.pickupsports.auth.service.RateLimiterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class SessionGuestIntegrationTest {

    @Autowired
    WebApplicationContext webApplicationContext;

    @MockitoBean
    RateLimiterService rateLimiterService;

    @Autowired
    JdbcTemplate jdbc;

    MockMvc mockMvc;

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        when(rateLimiterService.tryConsumeRequestLogin(any())).thenReturn(true);
        when(rateLimiterService.tryConsumeGuestJoin(any())).thenReturn(true);
    }

    // ── GET /sessions/{id} ──────────────────────────────────────────────────

    @Test
    void getSession_exists_returnsFullDetail() throws Exception {
        UUID hostId = insertUser("host@example.com", "Host Name");
        UUID sessionId = insertSession(hostId, "active", 10);
        insertParticipant(sessionId, hostId, null, null, null, "joined");

        mockMvc.perform(get("/api/v1/sessions/{id}", sessionId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(sessionId.toString()))
            .andExpect(jsonPath("$.sport").value("football"))
            .andExpect(jsonPath("$.host.name").value("Host Name"))
            .andExpect(jsonPath("$.capacity").value(10))
            .andExpect(jsonPath("$.spotsLeft").value(9))
            .andExpect(jsonPath("$.participants").isArray())
            .andExpect(jsonPath("$.participants[0].isGuest").value(false));
    }

    @Test
    void getSession_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/sessions/{id}", UUID.randomUUID()))
            .andExpect(status().isNotFound());
    }

    @Test
    void getSession_showsGuestParticipants() throws Exception {
        UUID hostId = insertUser("host2@example.com", "Host");
        UUID sessionId = insertSession(hostId, "active", 10);
        insertParticipant(sessionId, hostId, null, null, null, "joined");
        insertParticipant(sessionId, null, "Alex", UUID.randomUUID().toString(), null, "joined");

        mockMvc.perform(get("/api/v1/sessions/{id}", sessionId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.participants[1].name").value("Alex"))
            .andExpect(jsonPath("$.participants[1].isGuest").value(true))
            .andExpect(jsonPath("$.participants[1].id").doesNotExist());
    }

    // ── POST /sessions/{id}/guest-join ──────────────────────────────────────

    @Test
    void guestJoin_activeSession_returnsJoinedAndToken() throws Exception {
        UUID hostId = insertUser("host3@example.com", "Host");
        UUID sessionId = insertSession(hostId, "active", 10);

        mockMvc.perform(post("/api/v1/sessions/{id}/guest-join", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Alex\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("joined"))
            .andExpect(jsonPath("$.guestToken").isNotEmpty());
    }

    @Test
    void guestJoin_fullSession_returnsWaitlist() throws Exception {
        UUID hostId = insertUser("host4@example.com", "Host");
        UUID sessionId = insertSession(hostId, "active", 1);
        // Fill the single spot with the host
        insertParticipant(sessionId, hostId, null, null, null, "joined");

        mockMvc.perform(post("/api/v1/sessions/{id}/guest-join", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Alex\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("waitlist"))
            .andExpect(jsonPath("$.guestToken").isNotEmpty());
    }

    @Test
    void guestJoin_cancelledSession_returns400() throws Exception {
        UUID hostId = insertUser("host5@example.com", "Host");
        UUID sessionId = insertSession(hostId, "cancelled", 10);

        mockMvc.perform(post("/api/v1/sessions/{id}/guest-join", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Alex\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void guestJoin_missingName_returns400() throws Exception {
        UUID hostId = insertUser("host6@example.com", "Host");
        UUID sessionId = insertSession(hostId, "active", 10);

        mockMvc.perform(post("/api/v1/sessions/{id}/guest-join", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void guestJoin_rateLimited_returns429() throws Exception {
        UUID hostId = insertUser("host7@example.com", "Host");
        UUID sessionId = insertSession(hostId, "active", 10);
        when(rateLimiterService.tryConsumeGuestJoin(any())).thenReturn(false);

        mockMvc.perform(post("/api/v1/sessions/{id}/guest-join", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Alex\"}"))
            .andExpect(status().isTooManyRequests());
    }

    @Test
    void guestJoin_sessionNotFound_returns404() throws Exception {
        mockMvc.perform(post("/api/v1/sessions/{id}/guest-join", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Alex\"}"))
            .andExpect(status().isNotFound());
    }

    // ── POST /sessions/{id}/leave (guest) ───────────────────────────────────

    @Test
    void guestLeave_validToken_returns200() throws Exception {
        UUID hostId = insertUser("host8@example.com", "Host");
        UUID sessionId = insertSession(hostId, "active", 10);
        String guestToken = UUID.randomUUID().toString();
        insertParticipant(sessionId, null, "Alex", guestToken, null, "joined");

        mockMvc.perform(post("/api/v1/sessions/{id}/leave", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"guestToken\":\"" + guestToken + "\"}"))
            .andExpect(status().isOk());

        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM session_participants WHERE session_id = ? AND guest_token = ?",
            Integer.class, sessionId, guestToken
        );
        assertThat(count).isZero();
    }

    @Test
    void guestLeave_unknownToken_returns400() throws Exception {
        UUID hostId = insertUser("host9@example.com", "Host");
        UUID sessionId = insertSession(hostId, "active", 10);

        mockMvc.perform(post("/api/v1/sessions/{id}/leave", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"guestToken\":\"" + UUID.randomUUID() + "\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void guestLeave_noCredentials_returns400() throws Exception {
        UUID hostId = insertUser("host10@example.com", "Host");
        UUID sessionId = insertSession(hostId, "active", 10);

        mockMvc.perform(post("/api/v1/sessions/{id}/leave", sessionId))
            .andExpect(status().isBadRequest());
    }

    @Test
    void guestLeave_joinedGuest_promotesWaitlisted() throws Exception {
        UUID hostId = insertUser("host11@example.com", "Host");
        UUID sessionId = insertSession(hostId, "active", 1);
        // Fill the spot with the host
        insertParticipant(sessionId, hostId, null, null, null, "joined");
        // Guest joins → goes to waitlist
        MvcResult joinResult = mockMvc.perform(post("/api/v1/sessions/{id}/guest-join", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Waitlisted Guest\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("waitlist"))
            .andReturn();

        // Host leaves — now a spot is open
        String hostGuestToken = UUID.randomUUID().toString();
        // We need to remove the host participant manually (host can't leave via API, but we simulate spot opening)
        jdbc.update("DELETE FROM session_participants WHERE session_id = ? AND user_id = ?",
            sessionId, hostId);
        // Trigger promotion by having the waitlisted guest be promoted when another joined leaves
        // In this test, we simulate it via the API: add a joined guest and have them leave
        String joinerToken = UUID.randomUUID().toString();
        insertParticipant(sessionId, null, "Joiner", joinerToken, null, "joined");

        // Extract waitlisted guest token from join response
        String body = joinResult.getResponse().getContentAsString();
        String waitlistedToken = body.replaceAll(".*\"guestToken\":\"([^\"]+)\".*", "$1");

        // Joiner leaves → waitlisted guest should be promoted
        mockMvc.perform(post("/api/v1/sessions/{id}/leave", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"guestToken\":\"" + joinerToken + "\"}"))
            .andExpect(status().isOk());

        String status = jdbc.queryForObject(
            "SELECT status FROM session_participants WHERE session_id = ? AND guest_token = ?",
            String.class, sessionId, waitlistedToken
        );
        assertThat(status).isEqualTo("joined");
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

    private void insertParticipant(UUID sessionId, UUID userId, String guestName,
                                   String guestToken, UUID participantId, String status) {
        UUID id = participantId != null ? participantId : UUID.randomUUID();
        jdbc.update(
            """
            INSERT INTO session_participants
                (id, session_id, user_id, guest_name, guest_token, status, joined_at)
            VALUES (?, ?, ?, ?, ?, ?, NOW())
            """,
            id, sessionId, userId, guestName, guestToken, status
        );
    }
}
