package com.pickupsports.session;

import com.pickupsports.PostgresTestConfiguration;
import com.pickupsports.auth.service.EmailService;
import com.pickupsports.auth.service.JwtService;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class SessionManagementIntegrationTest {

    @Autowired
    WebApplicationContext webApplicationContext;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JwtService jwtService;

    @MockitoBean
    RateLimiterService rateLimiterService;

    @MockitoBean
    EmailService emailService;

    MockMvc mockMvc;

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        when(rateLimiterService.tryConsumeRequestLogin(any())).thenReturn(true);
        when(rateLimiterService.tryConsumeGuestJoin(any())).thenReturn(true);
    }

    // ── POST /sessions ───────────────────────────────────────────────────────

    @Test
    void createSession_validRequest_returns201WithHostAsParticipant() throws Exception {
        UUID hostId = insertUser("host-create@example.com", "Host User");
        String token = jwtService.generateToken(hostId, "host-create@example.com");

        MvcResult result = mockMvc.perform(post("/api/v1/sessions")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "sport": "football",
                        "title": "5-a-side at the park",
                        "notes": "Bring bibs",
                        "lat": 51.2,
                        "lng": 4.4,
                        "locationName": "Rivierenhof Park",
                        "startTime": "2030-06-01T18:00:00Z",
                        "endTime": "2030-06-01T20:00:00Z",
                        "capacity": 10
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.sport").value("football"))
            .andExpect(jsonPath("$.title").value("5-a-side at the park"))
            .andExpect(jsonPath("$.notes").value("Bring bibs"))
            .andExpect(jsonPath("$.locationName").value("Rivierenhof Park"))
            .andExpect(jsonPath("$.capacity").value(10))
            .andExpect(jsonPath("$.spotsLeft").value(9))
            .andExpect(jsonPath("$.status").value("active"))
            .andExpect(jsonPath("$.host.name").value("Host User"))
            .andExpect(jsonPath("$.participants").isArray())
            .andExpect(jsonPath("$.participants[0].isGuest").value(false))
            .andExpect(jsonPath("$.participants[0].status").value("joined"))
            .andReturn();

        // Verify host is actually in participants with status 'joined'
        String sessionId = com.jayway.jsonpath.JsonPath.read(
            result.getResponse().getContentAsString(), "$.id");
        Integer joinedCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM session_participants WHERE session_id = ?::uuid AND user_id = ? AND status = 'joined'",
            Integer.class, sessionId, hostId);
        assertThat(joinedCount).isEqualTo(1);
    }

    @Test
    void createSession_unauthorized_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "sport": "football",
                        "title": "Test",
                        "lat": 51.2, "lng": 4.4,
                        "locationName": "Park",
                        "startTime": "2030-06-01T18:00:00Z",
                        "endTime": "2030-06-01T20:00:00Z",
                        "capacity": 5
                    }
                    """))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void createSession_startTimeAfterEndTime_returns400() throws Exception {
        UUID hostId = insertUser("host-badtime@example.com", "Host");
        String token = jwtService.generateToken(hostId, "host-badtime@example.com");

        mockMvc.perform(post("/api/v1/sessions")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "sport": "football",
                        "title": "Test",
                        "lat": 51.2, "lng": 4.4,
                        "locationName": "Park",
                        "startTime": "2030-06-01T20:00:00Z",
                        "endTime": "2030-06-01T18:00:00Z",
                        "capacity": 5
                    }
                    """))
            .andExpect(status().isBadRequest());
    }

    @Test
    void createSession_capacityBelowOne_returns400() throws Exception {
        UUID hostId = insertUser("host-badcap@example.com", "Host");
        String token = jwtService.generateToken(hostId, "host-badcap@example.com");

        mockMvc.perform(post("/api/v1/sessions")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "sport": "football",
                        "title": "Test",
                        "lat": 51.2, "lng": 4.4,
                        "locationName": "Park",
                        "startTime": "2030-06-01T18:00:00Z",
                        "endTime": "2030-06-01T20:00:00Z",
                        "capacity": 0
                    }
                    """))
            .andExpect(status().isBadRequest());
    }

    // ── PUT /sessions/{id} ───────────────────────────────────────────────────

    @Test
    void updateSession_validRequest_returns200WithUpdatedFields() throws Exception {
        UUID hostId = insertUser("host-update@example.com", "Host");
        String token = jwtService.generateToken(hostId, "host-update@example.com");
        UUID sessionId = insertSession(hostId, "active", 10);
        insertParticipant(sessionId, hostId, null, null, "joined");

        mockMvc.perform(put("/api/v1/sessions/{id}", sessionId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "title": "Updated Title",
                        "notes": "Updated notes",
                        "capacity": 15
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.title").value("Updated Title"))
            .andExpect(jsonPath("$.notes").value("Updated notes"))
            .andExpect(jsonPath("$.capacity").value(15));
    }

    @Test
    void updateSession_nonHost_returns403() throws Exception {
        UUID hostId = insertUser("host-update2@example.com", "Host");
        UUID otherId = insertUser("other-update2@example.com", "Other");
        String otherToken = jwtService.generateToken(otherId, "other-update2@example.com");
        UUID sessionId = insertSession(hostId, "active", 10);

        mockMvc.perform(put("/api/v1/sessions/{id}", sessionId)
                .header("Authorization", "Bearer " + otherToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\": \"Hacked\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void updateSession_capacityBelowJoinedCount_returns400() throws Exception {
        UUID hostId = insertUser("host-update3@example.com", "Host");
        String token = jwtService.generateToken(hostId, "host-update3@example.com");
        UUID sessionId = insertSession(hostId, "active", 10);
        // Insert 3 joined participants (host + 2 others)
        insertParticipant(sessionId, hostId, null, null, "joined");
        UUID p2 = insertUser("p2-update3@example.com", "P2");
        UUID p3 = insertUser("p3-update3@example.com", "P3");
        insertParticipant(sessionId, p2, null, null, "joined");
        insertParticipant(sessionId, p3, null, null, "joined");

        // Try to set capacity to 2 (below joined count of 3)
        mockMvc.perform(put("/api/v1/sessions/{id}", sessionId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"capacity\": 2}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void updateSession_cancelledSession_returns400() throws Exception {
        UUID hostId = insertUser("host-update4@example.com", "Host");
        String token = jwtService.generateToken(hostId, "host-update4@example.com");
        UUID sessionId = insertSession(hostId, "cancelled", 10);

        mockMvc.perform(put("/api/v1/sessions/{id}", sessionId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\": \"New Title\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void updateSession_unauthorized_returns401() throws Exception {
        UUID hostId = insertUser("host-update5@example.com", "Host");
        UUID sessionId = insertSession(hostId, "active", 10);

        mockMvc.perform(put("/api/v1/sessions/{id}", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\": \"New Title\"}"))
            .andExpect(status().isUnauthorized());
    }

    // ── DELETE /sessions/{id} ────────────────────────────────────────────────

    @Test
    void cancelSession_asHost_returns200AndStatusIsCancelled() throws Exception {
        UUID hostId = insertUser("host-cancel@example.com", "Host");
        String token = jwtService.generateToken(hostId, "host-cancel@example.com");
        UUID sessionId = insertSession(hostId, "active", 10);
        insertParticipant(sessionId, hostId, null, null, "joined");

        mockMvc.perform(delete("/api/v1/sessions/{id}", sessionId)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk());

        String status = jdbc.queryForObject(
            "SELECT status FROM sessions WHERE id = ?", String.class, sessionId);
        assertThat(status).isEqualTo("cancelled");
    }

    @Test
    void cancelSession_nonHost_returns403() throws Exception {
        UUID hostId = insertUser("host-cancel2@example.com", "Host");
        UUID otherId = insertUser("other-cancel2@example.com", "Other");
        String otherToken = jwtService.generateToken(otherId, "other-cancel2@example.com");
        UUID sessionId = insertSession(hostId, "active", 10);

        mockMvc.perform(delete("/api/v1/sessions/{id}", sessionId)
                .header("Authorization", "Bearer " + otherToken))
            .andExpect(status().isForbidden());
    }

    @Test
    void cancelSession_unauthorized_returns401() throws Exception {
        UUID hostId = insertUser("host-cancel3@example.com", "Host");
        UUID sessionId = insertSession(hostId, "active", 10);

        mockMvc.perform(delete("/api/v1/sessions/{id}", sessionId))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void cancelSession_alreadyCancelled_returns400() throws Exception {
        UUID hostId = insertUser("host-cancel4@example.com", "Host");
        String token = jwtService.generateToken(hostId, "host-cancel4@example.com");
        UUID sessionId = insertSession(hostId, "cancelled", 10);
        insertParticipant(sessionId, hostId, null, null, "joined");

        mockMvc.perform(delete("/api/v1/sessions/{id}", sessionId)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isBadRequest());
    }

    @Test
    void cancelSession_sendsEmailsToRegisteredNonHostParticipants() throws Exception {
        UUID hostId = insertUser("host-notify@example.com", "Host");
        String token = jwtService.generateToken(hostId, "host-notify@example.com");
        UUID sessionId = insertSession(hostId, "active", 10);
        insertParticipant(sessionId, hostId, null, null, "joined");

        // Add a registered participant
        UUID participantId = insertUser("participant-notify@example.com", "Participant");
        insertParticipant(sessionId, participantId, null, null, "joined");

        // Add a guest participant — should NOT receive email
        insertParticipant(sessionId, null, "Guest User", UUID.randomUUID().toString(), "joined");

        mockMvc.perform(delete("/api/v1/sessions/{id}", sessionId)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk());

        // Participant should receive cancellation email
        verify(emailService).sendCancellationNotification(
            eq("participant-notify@example.com"),
            anyString(), any(Instant.class), anyString()
        );

        // Host should NOT receive cancellation email (they initiated it)
        verify(emailService, never()).sendCancellationNotification(
            eq("host-notify@example.com"),
            anyString(), any(Instant.class), anyString()
        );
    }

    // ── helpers ──────────────────────────────────────────────────────────────

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
                                   String guestToken, String status) {
        UUID id = UUID.randomUUID();
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
