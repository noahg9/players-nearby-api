package com.pickupsports.user;

import com.pickupsports.PostgresTestConfiguration;
import com.pickupsports.auth.service.EmailService;
import com.pickupsports.auth.service.JwtService;
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

import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class AccountDeletionIntegrationTest {

    @Autowired WebApplicationContext webApplicationContext;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwtService;
    @MockitoBean EmailService emailService;

    MockMvc mockMvc;

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private UUID insertUser(String name, String email) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, name) VALUES (?, ?, ?)", id, email, name);
        return id;
    }

    private UUID insertSession(UUID hostUserId, String status) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
            INSERT INTO sessions
                (id, sport, title, status, start_time, end_time, capacity,
                 host_user_id, location, location_name)
            VALUES (?, 'football', 'Test Session', ?, ?, ?, 10,
                    ?, ST_SetSRID(ST_MakePoint(4.4, 51.2), 4326), 'Test Location')
            """,
            id, status,
            Timestamp.from(now.plus(1, ChronoUnit.HOURS)),
            Timestamp.from(now.plus(2, ChronoUnit.HOURS)),
            hostUserId);
        return id;
    }

    private void insertParticipant(UUID sessionId, UUID userId, String status) {
        jdbc.update("""
            INSERT INTO session_participants (id, session_id, user_id, status, joined_at)
            VALUES (?, ?, ?, ?, NOW())
            """, UUID.randomUUID(), sessionId, userId, status);
    }

    private void insertGuestParticipant(UUID sessionId, String guestName) {
        jdbc.update("""
            INSERT INTO session_participants
                (id, session_id, guest_name, guest_token, status, joined_at)
            VALUES (?, ?, ?, ?, 'joined', NOW())
            """, UUID.randomUUID(), sessionId, guestName, UUID.randomUUID().toString());
    }

    private String bearerToken(UUID userId, String email) {
        return "Bearer " + jwtService.generateToken(userId, email);
    }

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    void deleteAccount_success_returns200AndDeletesUser() throws Exception {
        UUID userId = insertUser("Alice", "alice-" + UUID.randomUUID() + "@example.com");

        mockMvc.perform(delete("/api/v1/users/me")
                .header("Authorization", bearerToken(userId, "alice@example.com")))
            .andExpect(status().isOk());

        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, userId);
        assertThat(count).isZero();
    }

    @Test
    void deleteAccount_cancelsActiveHostedSessions() throws Exception {
        UUID hostId = insertUser("Host", "host-" + UUID.randomUUID() + "@example.com");
        UUID activeSession1 = insertSession(hostId, "active");
        UUID activeSession2 = insertSession(hostId, "active");
        UUID cancelledSession = insertSession(hostId, "cancelled");
        insertParticipant(activeSession1, hostId, "joined");
        insertParticipant(activeSession2, hostId, "joined");
        insertParticipant(cancelledSession, hostId, "joined");

        mockMvc.perform(delete("/api/v1/users/me")
                .header("Authorization", bearerToken(hostId, "host@example.com")))
            .andExpect(status().isOk());

        String status1 = jdbc.queryForObject(
            "SELECT status FROM sessions WHERE id = ?", String.class, activeSession1);
        String status2 = jdbc.queryForObject(
            "SELECT status FROM sessions WHERE id = ?", String.class, activeSession2);
        String statusCancelled = jdbc.queryForObject(
            "SELECT status FROM sessions WHERE id = ?", String.class, cancelledSession);

        assertThat(status1).isEqualTo("cancelled");
        assertThat(status2).isEqualTo("cancelled");
        assertThat(statusCancelled).isEqualTo("cancelled"); // unchanged
    }

    @Test
    void deleteAccount_sendsNotificationsToRegisteredParticipants() throws Exception {
        String hostEmail = "host-" + UUID.randomUUID() + "@example.com";
        String p1Email = "p1-" + UUID.randomUUID() + "@example.com";
        String p2Email = "p2-" + UUID.randomUUID() + "@example.com";

        UUID hostId = insertUser("Host", hostEmail);
        UUID participant1Id = insertUser("P1", p1Email);
        UUID participant2Id = insertUser("P2", p2Email);

        UUID sessionId = insertSession(hostId, "active");
        insertParticipant(sessionId, hostId, "joined");
        insertParticipant(sessionId, participant1Id, "joined");
        insertParticipant(sessionId, participant2Id, "joined");

        mockMvc.perform(delete("/api/v1/users/me")
                .header("Authorization", bearerToken(hostId, hostEmail)))
            .andExpect(status().isOk());

        // Exactly 2 non-host participants notified with correct email addresses
        ArgumentCaptor<String> emailCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService, times(2)).sendCancellationNotification(
            emailCaptor.capture(), anyString(), any(Instant.class), anyString());
        assertThat(emailCaptor.getAllValues()).containsExactlyInAnyOrder(p1Email, p2Email);
    }

    @Test
    void deleteAccount_notifiesWaitlistParticipants() throws Exception {
        String hostEmail = "host-" + UUID.randomUUID() + "@example.com";
        String waitlistEmail = "waitlist-" + UUID.randomUUID() + "@example.com";

        UUID hostId = insertUser("Host", hostEmail);
        UUID waitlistUserId = insertUser("Waiter", waitlistEmail);

        UUID sessionId = insertSession(hostId, "active");
        insertParticipant(sessionId, hostId, "joined");
        insertParticipant(sessionId, waitlistUserId, "waitlist");

        mockMvc.perform(delete("/api/v1/users/me")
                .header("Authorization", bearerToken(hostId, hostEmail)))
            .andExpect(status().isOk());

        // Waitlist participant must be notified (AC #3: status 'joined' OR 'waitlist')
        verify(emailService, times(1)).sendCancellationNotification(
            eq(waitlistEmail), anyString(), any(Instant.class), anyString());
    }

    @Test
    void deleteAccount_doesNotNotifyGuests() throws Exception {
        UUID hostId = insertUser("Host", "host-" + UUID.randomUUID() + "@example.com");
        UUID sessionId = insertSession(hostId, "active");
        insertParticipant(sessionId, hostId, "joined");
        insertGuestParticipant(sessionId, "GuestAlex");

        mockMvc.perform(delete("/api/v1/users/me")
                .header("Authorization", bearerToken(hostId, "host@example.com")))
            .andExpect(status().isOk());

        verify(emailService, never()).sendCancellationNotification(
            anyString(), anyString(), any(Instant.class), anyString());
    }

    @Test
    void deleteAccount_doesNotNotifyHostThemself() throws Exception {
        String hostEmail = "host-self-" + UUID.randomUUID() + "@example.com";
        UUID hostId = insertUser("Host", hostEmail);
        UUID sessionId = insertSession(hostId, "active");
        insertParticipant(sessionId, hostId, "joined");

        mockMvc.perform(delete("/api/v1/users/me")
                .header("Authorization", bearerToken(hostId, hostEmail)))
            .andExpect(status().isOk());

        verify(emailService, never()).sendCancellationNotification(
            eq(hostEmail), anyString(), any(Instant.class), anyString());
    }

    @Test
    void deleteAccount_skipsAlreadyCancelledAndCompletedSessions() throws Exception {
        UUID hostId = insertUser("Host", "host-" + UUID.randomUUID() + "@example.com");
        UUID participant1Id = insertUser("P1", "p1-" + UUID.randomUUID() + "@example.com");

        UUID cancelledSession = insertSession(hostId, "cancelled");
        UUID completedSession = insertSession(hostId, "completed");
        insertParticipant(cancelledSession, participant1Id, "joined");
        insertParticipant(completedSession, participant1Id, "joined");

        mockMvc.perform(delete("/api/v1/users/me")
                .header("Authorization", bearerToken(hostId, "host@example.com")))
            .andExpect(status().isOk());

        verify(emailService, never()).sendCancellationNotification(
            anyString(), anyString(), any(Instant.class), anyString());

        // Sessions still exist in DB with their original status
        String cancelledStatus = jdbc.queryForObject(
            "SELECT status FROM sessions WHERE id = ?", String.class, cancelledSession);
        String completedStatus = jdbc.queryForObject(
            "SELECT status FROM sessions WHERE id = ?", String.class, completedSession);
        assertThat(cancelledStatus).isEqualTo("cancelled");
        assertThat(completedStatus).isEqualTo("completed");
    }

    @Test
    void deleteAccount_deletesUserSportProfiles() throws Exception {
        UUID userId = insertUser("Alice", "alice-sports-" + UUID.randomUUID() + "@example.com");
        jdbc.update(
            "INSERT INTO user_sport_profiles (user_id, sport, skill_level) VALUES (?, 'football', 'beginner')",
            userId);

        mockMvc.perform(delete("/api/v1/users/me")
                .header("Authorization", bearerToken(userId, "alice@example.com")))
            .andExpect(status().isOk());

        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM user_sport_profiles WHERE user_id = ?", Integer.class, userId);
        assertThat(count).isZero();
    }

    @Test
    void deleteAccount_deletesParticipantRecords() throws Exception {
        UUID hostId = insertUser("Host", "host-" + UUID.randomUUID() + "@example.com");
        UUID participantId = insertUser("Participant", "participant-" + UUID.randomUUID() + "@example.com");

        UUID sessionId = insertSession(hostId, "active");
        insertParticipant(sessionId, hostId, "joined");
        insertParticipant(sessionId, participantId, "joined");

        mockMvc.perform(delete("/api/v1/users/me")
                .header("Authorization", bearerToken(participantId, "participant@example.com")))
            .andExpect(status().isOk());

        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM session_participants WHERE user_id = ?", Integer.class, participantId);
        assertThat(count).isZero();

        // Session itself still exists
        Integer sessionCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM sessions WHERE id = ?", Integer.class, sessionId);
        assertThat(sessionCount).isOne();
    }

    @Test
    void deleteAccount_hostedSessionsPersistedWithNullHostUserId() throws Exception {
        UUID hostId = insertUser("Host", "host-" + UUID.randomUUID() + "@example.com");
        UUID sessionId = insertSession(hostId, "active");
        insertParticipant(sessionId, hostId, "joined");

        mockMvc.perform(delete("/api/v1/users/me")
                .header("Authorization", bearerToken(hostId, "host@example.com")))
            .andExpect(status().isOk());

        // Session persists with host_user_id = NULL (SET NULL via V6 migration)
        Integer sessionCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM sessions WHERE id = ?", Integer.class, sessionId);
        assertThat(sessionCount).isOne();

        UUID hostUserId = jdbc.queryForObject(
            "SELECT host_user_id FROM sessions WHERE id = ?", UUID.class, sessionId);
        assertThat(hostUserId).isNull();
    }

    @Test
    void deleteAccount_noToken_returns401() throws Exception {
        mockMvc.perform(delete("/api/v1/users/me"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void deleteAccount_invalidToken_returns401() throws Exception {
        mockMvc.perform(delete("/api/v1/users/me")
                .header("Authorization", "Bearer invalid-token"))
            .andExpect(status().isUnauthorized());
    }
}
