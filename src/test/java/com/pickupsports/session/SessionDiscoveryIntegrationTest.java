package com.pickupsports.session;

import com.pickupsports.PostgresTestConfiguration;
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
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class SessionDiscoveryIntegrationTest {

    @Autowired
    WebApplicationContext webApplicationContext;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    RateLimiterService rateLimiterService;

    MockMvc mockMvc;

    // Antwerp coordinates — all test sessions are inserted here
    private static final double ANTWERP_LAT = 51.2;
    private static final double ANTWERP_LNG = 4.4;

    // Brussels coordinates — ~35km from Antwerp, well outside default 5km radius
    private static final double BRUSSELS_LAT = 50.85;
    private static final double BRUSSELS_LNG = 4.35;

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        when(rateLimiterService.tryConsumeRequestLogin(any())).thenReturn(true);
        when(rateLimiterService.tryConsumeGuestJoin(any())).thenReturn(true);
        // Clean DB state before each test — sessions cascade-deletes session_participants;
        // users cascade-deletes user_sport_profiles
        jdbc.update("DELETE FROM sessions");
        jdbc.update("DELETE FROM users");
    }

    // ── GET /sessions ────────────────────────────────────────────────────────

    @Test
    void discoverSessions_nearbyActive_returnsMatchingSessions() throws Exception {
        insertSession("football", "active", "2030-01-01T10:00:00Z", "2030-01-01T12:00:00Z", 10);

        mockMvc.perform(get("/api/v1/sessions")
                .param("lat", String.valueOf(ANTWERP_LAT))
                .param("lng", String.valueOf(ANTWERP_LNG))
                .param("from", "2030-01-01T00:00:00Z"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.content[0].sport").value("football"))
            .andExpect(jsonPath("$.content[0].status").value("active"));
    }

    @Test
    void discoverSessions_excludesCancelledAndCompleted() throws Exception {
        insertSession("football", "active",    "2030-01-01T10:00:00Z", "2030-01-01T12:00:00Z", 10);
        insertSession("football", "cancelled", "2030-01-01T10:00:00Z", "2030-01-01T12:00:00Z", 10);
        insertSession("football", "completed", "2030-01-01T10:00:00Z", "2030-01-01T12:00:00Z", 10);

        mockMvc.perform(get("/api/v1/sessions")
                .param("lat", String.valueOf(ANTWERP_LAT))
                .param("lng", String.valueOf(ANTWERP_LNG))
                .param("from", "2030-01-01T00:00:00Z"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.content[0].status").value("active"));
    }

    @Test
    void discoverSessions_outsideRadius_returnsEmpty() throws Exception {
        insertSession("football", "active", "2030-01-01T10:00:00Z", "2030-01-01T12:00:00Z", 10);

        mockMvc.perform(get("/api/v1/sessions")
                .param("lat", String.valueOf(BRUSSELS_LAT))
                .param("lng", String.valueOf(BRUSSELS_LNG))
                .param("from", "2030-01-01T00:00:00Z"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(0))
            .andExpect(jsonPath("$.content").isEmpty());
    }

    @Test
    void discoverSessions_filterBySport_returnsMatchingOnly() throws Exception {
        insertSession("football",   "active", "2030-01-01T10:00:00Z", "2030-01-01T12:00:00Z", 10);
        insertSession("basketball", "active", "2030-01-01T10:00:00Z", "2030-01-01T12:00:00Z", 10);

        mockMvc.perform(get("/api/v1/sessions")
                .param("lat", String.valueOf(ANTWERP_LAT))
                .param("lng", String.valueOf(ANTWERP_LNG))
                .param("from", "2030-01-01T00:00:00Z")
                .param("sport", "football"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.content[0].sport").value("football"));
    }

    @Test
    void discoverSessions_filterByFrom_excludesSessionsBeforeCutoff() throws Exception {
        insertSession("football", "active", "2030-01-01T10:00:00Z", "2030-01-01T12:00:00Z", 10);
        insertSession("football", "active", "2030-06-01T10:00:00Z", "2030-06-01T12:00:00Z", 10);

        // from=2030-03-01 should exclude the January session
        mockMvc.perform(get("/api/v1/sessions")
                .param("lat", String.valueOf(ANTWERP_LAT))
                .param("lng", String.valueOf(ANTWERP_LNG))
                .param("from", "2030-03-01T00:00:00Z"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.content[0].startTime").value("2030-06-01T10:00:00Z"));
    }

    @Test
    void discoverSessions_filterByTo_excludesSessionsAfterCutoff() throws Exception {
        insertSession("football", "active", "2030-01-01T10:00:00Z", "2030-01-01T12:00:00Z", 10);
        insertSession("football", "active", "2030-06-01T10:00:00Z", "2030-06-01T12:00:00Z", 10);

        // to=2030-03-01 should exclude the June session
        mockMvc.perform(get("/api/v1/sessions")
                .param("lat", String.valueOf(ANTWERP_LAT))
                .param("lng", String.valueOf(ANTWERP_LNG))
                .param("from", "2030-01-01T00:00:00Z")
                .param("to", "2030-03-01T00:00:00Z"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.content[0].startTime").value("2030-01-01T10:00:00Z"));
    }

    @Test
    void discoverSessions_pagination_returnsCorrectPage() throws Exception {
        // Insert 3 sessions in chronological order
        insertSession("football", "active", "2030-01-01T10:00:00Z", "2030-01-01T12:00:00Z", 10);
        insertSession("football", "active", "2030-01-02T10:00:00Z", "2030-01-02T12:00:00Z", 10);
        insertSession("football", "active", "2030-01-03T10:00:00Z", "2030-01-03T12:00:00Z", 10);

        // Page 0, size 2 → first two sessions, total = 3
        mockMvc.perform(get("/api/v1/sessions")
                .param("lat", String.valueOf(ANTWERP_LAT))
                .param("lng", String.valueOf(ANTWERP_LNG))
                .param("from", "2030-01-01T00:00:00Z")
                .param("page", "0")
                .param("size", "2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(2))
            .andExpect(jsonPath("$.total").value(3))
            .andExpect(jsonPath("$.content.length()").value(2));

        // Page 1, size 2 → third session only
        mockMvc.perform(get("/api/v1/sessions")
                .param("lat", String.valueOf(ANTWERP_LAT))
                .param("lng", String.valueOf(ANTWERP_LNG))
                .param("from", "2030-01-01T00:00:00Z")
                .param("page", "1")
                .param("size", "2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.page").value(1))
            .andExpect(jsonPath("$.total").value(3))
            .andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    void discoverSessions_missingLat_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/sessions").param("lng", "4.4"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void discoverSessions_missingLng_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/sessions").param("lat", "51.2"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void discoverSessions_negativePage_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/sessions")
                .param("lat", String.valueOf(ANTWERP_LAT))
                .param("lng", String.valueOf(ANTWERP_LNG))
                .param("page", "-1"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void discoverSessions_zeroSize_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/sessions")
                .param("lat", String.valueOf(ANTWERP_LAT))
                .param("lng", String.valueOf(ANTWERP_LNG))
                .param("size", "0"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void discoverSessions_oversizedSize_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/sessions")
                .param("lat", String.valueOf(ANTWERP_LAT))
                .param("lng", String.valueOf(ANTWERP_LNG))
                .param("size", "101"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void discoverSessions_excessiveRadius_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/sessions")
                .param("lat", String.valueOf(ANTWERP_LAT))
                .param("lng", String.valueOf(ANTWERP_LNG))
                .param("radius", "100001"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void discoverSessions_resultsOrderedByStartTimeAscending() throws Exception {
        // Insert sessions in reverse chronological order to confirm ORDER BY is applied
        insertSession("football", "active", "2030-01-03T10:00:00Z", "2030-01-03T12:00:00Z", 10);
        insertSession("football", "active", "2030-01-01T10:00:00Z", "2030-01-01T12:00:00Z", 10);
        insertSession("football", "active", "2030-01-02T10:00:00Z", "2030-01-02T12:00:00Z", 10);

        mockMvc.perform(get("/api/v1/sessions")
                .param("lat", String.valueOf(ANTWERP_LAT))
                .param("lng", String.valueOf(ANTWERP_LNG))
                .param("from", "2030-01-01T00:00:00Z"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(3))
            .andExpect(jsonPath("$.content[0].startTime").value("2030-01-01T10:00:00Z"))
            .andExpect(jsonPath("$.content[1].startTime").value("2030-01-02T10:00:00Z"))
            .andExpect(jsonPath("$.content[2].startTime").value("2030-01-03T10:00:00Z"));
    }

    @Test
    void discoverSessions_participantCountAndSpotsLeft_areCorrect() throws Exception {
        UUID sessionId = insertSession("basketball", "active",
            "2030-01-01T10:00:00Z", "2030-01-01T12:00:00Z", 5);
        UUID u1 = insertUser("p1-discovery@test.com", "P1");
        UUID u2 = insertUser("p2-discovery@test.com", "P2");
        insertParticipant(sessionId, u1, "joined");
        insertParticipant(sessionId, u2, "joined");
        // Insert a waitlisted participant — should NOT count toward participantCount
        UUID u3 = insertUser("p3-discovery@test.com", "P3");
        insertParticipant(sessionId, u3, "waitlist");

        mockMvc.perform(get("/api/v1/sessions")
                .param("lat", String.valueOf(ANTWERP_LAT))
                .param("lng", String.valueOf(ANTWERP_LNG))
                .param("from", "2030-01-01T00:00:00Z")
                .param("sport", "basketball"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.content[0].participantCount").value(2))
            .andExpect(jsonPath("$.content[0].spotsLeft").value(3))
            .andExpect(jsonPath("$.content[0].capacity").value(5));
    }

    @Test
    void discoverSessions_responseIncludesExpectedFields() throws Exception {
        insertSession("tennis", "active", "2030-01-01T10:00:00Z", "2030-01-01T12:00:00Z", 8);

        mockMvc.perform(get("/api/v1/sessions")
                .param("lat", String.valueOf(ANTWERP_LAT))
                .param("lng", String.valueOf(ANTWERP_LNG))
                .param("from", "2030-01-01T00:00:00Z"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].id").isNotEmpty())
            .andExpect(jsonPath("$.content[0].sport").value("tennis"))
            .andExpect(jsonPath("$.content[0].title").isNotEmpty())
            .andExpect(jsonPath("$.content[0].locationName").isNotEmpty())
            .andExpect(jsonPath("$.content[0].location.lat").isNumber())
            .andExpect(jsonPath("$.content[0].location.lng").isNumber())
            .andExpect(jsonPath("$.content[0].startTime").isNotEmpty())
            .andExpect(jsonPath("$.content[0].endTime").isNotEmpty())
            .andExpect(jsonPath("$.content[0].capacity").value(8))
            .andExpect(jsonPath("$.content[0].participantCount").isNumber())
            .andExpect(jsonPath("$.content[0].spotsLeft").isNumber())
            .andExpect(jsonPath("$.content[0].status").value("active"));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private UUID insertUser(String email, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, name) VALUES (?, ?, ?)", id, email, name);
        return id;
    }

    private UUID insertSession(String sport, String status, String startTime, String endTime, int capacity) {
        UUID sessionId = UUID.randomUUID();
        UUID hostId = insertUser("host-" + sessionId + "@test.com", "Host");
        jdbc.update(
            """
            INSERT INTO sessions
                (id, sport, title, status, start_time, end_time, capacity,
                 host_user_id, location, location_name)
            VALUES (?, ?, 'Test Session', ?, ?, ?, ?,
                    ?, ST_SetSRID(ST_MakePoint(?, ?), 4326), 'Test Location')
            """,
            sessionId, sport, status,
            Timestamp.from(Instant.parse(startTime)),
            Timestamp.from(Instant.parse(endTime)),
            capacity,
            hostId,
            ANTWERP_LNG, ANTWERP_LAT  // ST_MakePoint(lng, lat)
        );
        return sessionId;
    }

    private void insertParticipant(UUID sessionId, UUID userId, String status) {
        UUID participantId = UUID.randomUUID();
        jdbc.update(
            """
            INSERT INTO session_participants (id, session_id, user_id, status, joined_at)
            VALUES (?, ?, ?, ?, NOW())
            """,
            participantId, sessionId, userId, status
        );
    }
}
