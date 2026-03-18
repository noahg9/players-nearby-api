package com.pickupsports.chat;

import com.pickupsports.PostgresTestConfiguration;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class ChatIntegrationTest {

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
        when(rateLimiterService.tryConsumeChat(any())).thenReturn(true);
    }

    // ── GET /sessions/{id}/messages ─────────────────────────────────────────

    @Test
    void getMessages_existingSession_returnsEmptyList() throws Exception {
        UUID hostId = insertUser("chat-host1@example.com", "Host One");
        UUID sessionId = insertSession(hostId, "active", 10);

        mockMvc.perform(get("/api/v1/sessions/{id}/messages", sessionId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").isArray())
            .andExpect(jsonPath("$.content.length()").value(0))
            .andExpect(jsonPath("$.total").value(0))
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(50));
    }

    @Test
    void getMessages_sessionNotFound_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/sessions/{id}/messages", UUID.randomUUID()))
            .andExpect(status().isNotFound());
    }

    // ── POST /sessions/{id}/messages — auth user ────────────────────────────

    @Test
    void postMessage_authUser_joined_returns201() throws Exception {
        UUID userId = insertUser("chat-user1@example.com", "Alice");
        UUID sessionId = insertSession(userId, "active", 10);
        insertParticipant(sessionId, userId, null, null, "joined");
        String token = jwtService.generateToken(userId, "chat-user1@example.com");

        mockMvc.perform(post("/api/v1/sessions/{id}/messages", sessionId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"See you there!\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").isNotEmpty())
            .andExpect(jsonPath("$.authorName").value("Alice"))
            .andExpect(jsonPath("$.content").value("See you there!"))
            .andExpect(jsonPath("$.sentAt").isNotEmpty());
    }

    @Test
    void postMessage_authUser_waitlisted_returns403() throws Exception {
        UUID hostId = insertUser("chat-host2@example.com", "Host");
        UUID sessionId = insertSession(hostId, "active", 1);
        insertParticipant(sessionId, hostId, null, null, "joined");

        UUID waitlistedId = insertUser("chat-waitlist1@example.com", "Waitlisted");
        insertParticipant(sessionId, waitlistedId, null, null, "waitlist");
        String token = jwtService.generateToken(waitlistedId, "chat-waitlist1@example.com");

        mockMvc.perform(post("/api/v1/sessions/{id}/messages", sessionId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"Can I play?\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void postMessage_authUser_notParticipant_returns403() throws Exception {
        UUID hostId = insertUser("chat-host3@example.com", "Host");
        UUID sessionId = insertSession(hostId, "active", 10);
        insertParticipant(sessionId, hostId, null, null, "joined");

        UUID outsiderId = insertUser("chat-outsider1@example.com", "Outsider");
        String token = jwtService.generateToken(outsiderId, "chat-outsider1@example.com");

        mockMvc.perform(post("/api/v1/sessions/{id}/messages", sessionId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"Hello!\"}"))
            .andExpect(status().isForbidden());
    }

    // ── POST /sessions/{id}/messages — guest user ───────────────────────────

    @Test
    void postMessage_guestUser_joined_returns201() throws Exception {
        UUID hostId = insertUser("chat-host4@example.com", "Host");
        UUID sessionId = insertSession(hostId, "active", 10);
        String guestToken = UUID.randomUUID().toString();
        insertParticipant(sessionId, null, "GuestBob", guestToken, "joined");

        mockMvc.perform(post("/api/v1/sessions/{id}/messages", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"I'll be there!\",\"guestToken\":\"" + guestToken + "\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.authorName").value("GuestBob"))
            .andExpect(jsonPath("$.content").value("I'll be there!"));
    }

    // ── POST /sessions/{id}/messages — session not found ────────────────────

    @Test
    void postMessage_sessionNotFound_returns404() throws Exception {
        UUID userId = insertUser("chat-404user@example.com", "Ghost");
        String token = jwtService.generateToken(userId, "chat-404user@example.com");

        mockMvc.perform(post("/api/v1/sessions/{id}/messages", UUID.randomUUID())
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"Is anyone there?\"}"))
            .andExpect(status().isNotFound());
    }

    // ── POST /sessions/{id}/messages — rate limiting ─────────────────────────

    @Test
    void postMessage_rateLimitExceeded_returns429() throws Exception {
        UUID userId = insertUser("chat-ratelimit@example.com", "Spammer");
        UUID sessionId = insertSession(userId, "active", 10);
        insertParticipant(sessionId, userId, null, null, "joined");
        String token = jwtService.generateToken(userId, "chat-ratelimit@example.com");
        when(rateLimiterService.tryConsumeChat(any())).thenReturn(false);

        mockMvc.perform(post("/api/v1/sessions/{id}/messages", sessionId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"Too many messages!\"}"))
            .andExpect(status().isTooManyRequests());
    }

    // ── POST /sessions/{id}/messages — auth errors ──────────────────────────

    @Test
    void postMessage_noAuthNoGuestToken_returns401() throws Exception {
        UUID hostId = insertUser("chat-host5@example.com", "Host");
        UUID sessionId = insertSession(hostId, "active", 10);

        mockMvc.perform(post("/api/v1/sessions/{id}/messages", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"Hello!\"}"))
            .andExpect(status().isUnauthorized());
    }

    // ── POST /sessions/{id}/messages — validation ───────────────────────────

    @Test
    void postMessage_blankContent_returns400() throws Exception {
        UUID userId = insertUser("chat-user2@example.com", "Bob");
        UUID sessionId = insertSession(userId, "active", 10);
        insertParticipant(sessionId, userId, null, null, "joined");
        String token = jwtService.generateToken(userId, "chat-user2@example.com");

        mockMvc.perform(post("/api/v1/sessions/{id}/messages", sessionId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void postMessage_contentTooLong_returns400() throws Exception {
        UUID userId = insertUser("chat-user3@example.com", "Carol");
        UUID sessionId = insertSession(userId, "active", 10);
        insertParticipant(sessionId, userId, null, null, "joined");
        String token = jwtService.generateToken(userId, "chat-user3@example.com");

        String longContent = "x".repeat(501);
        mockMvc.perform(post("/api/v1/sessions/{id}/messages", sessionId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"" + longContent + "\"}"))
            .andExpect(status().isBadRequest());
    }

    // ── GET after POST — messages appear ordered ─────────────────────────────

    @Test
    void getMessages_afterPost_messagesAppearOrdered() throws Exception {
        UUID userId = insertUser("chat-user4@example.com", "Dave");
        UUID sessionId = insertSession(userId, "active", 10);
        insertParticipant(sessionId, userId, null, null, "joined");
        String token = jwtService.generateToken(userId, "chat-user4@example.com");

        mockMvc.perform(post("/api/v1/sessions/{id}/messages", sessionId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"First message\"}"))
            .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/sessions/{id}/messages", sessionId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"Second message\"}"))
            .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/sessions/{id}/messages", sessionId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(2))
            .andExpect(jsonPath("$.total").value(2))
            .andExpect(jsonPath("$.content[0].content").value("First message"))
            .andExpect(jsonPath("$.content[1].content").value("Second message"));
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
                                    String guestToken, String status) {
        jdbc.update(
            """
            INSERT INTO session_participants
                (id, session_id, user_id, guest_name, guest_token, status, joined_at)
            VALUES (?, ?, ?, ?, ?, ?, NOW())
            """,
            UUID.randomUUID(), sessionId, userId, guestName, guestToken, status
        );
    }
}
