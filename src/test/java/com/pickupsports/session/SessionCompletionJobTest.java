package com.pickupsports.session;

import com.pickupsports.PostgresTestConfiguration;
import com.pickupsports.auth.service.EmailService;
import com.pickupsports.auth.service.RateLimiterService;
import com.pickupsports.session.job.SessionCompletionJob;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class SessionCompletionJobTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SessionCompletionJob sessionCompletionJob;

    @MockitoBean
    RateLimiterService rateLimiterService;

    @MockitoBean
    EmailService emailService;

    @Test
    void markExpiredSessions_completesExpiredActiveSessions() {
        UUID expiredSession = insertSession("active", Instant.now().minus(1, ChronoUnit.HOURS));
        UUID futureSession  = insertSession("active", Instant.now().plus(2, ChronoUnit.HOURS));

        sessionCompletionJob.markExpiredSessions();

        String expiredStatus = jdbc.queryForObject(
            "SELECT status FROM sessions WHERE id = ?", String.class, expiredSession);
        String futureStatus = jdbc.queryForObject(
            "SELECT status FROM sessions WHERE id = ?", String.class, futureSession);

        assertThat(expiredStatus).isEqualTo("completed");
        assertThat(futureStatus).isEqualTo("active");
    }

    @Test
    void markExpiredSessions_doesNotAffectCancelledSessions() {
        UUID cancelledSession = insertSession("cancelled", Instant.now().minus(1, ChronoUnit.HOURS));

        sessionCompletionJob.markExpiredSessions();

        String status = jdbc.queryForObject(
            "SELECT status FROM sessions WHERE id = ?", String.class, cancelledSession);
        assertThat(status).isEqualTo("cancelled");
    }

    private UUID insertSession(String status, Instant endTime) {
        UUID id = UUID.randomUUID();
        Instant startTime = endTime.minus(2, ChronoUnit.HOURS);
        jdbc.update("""
            INSERT INTO sessions
                (id, sport, title, status, start_time, end_time, capacity,
                 host_user_id, location, location_name)
            VALUES (?, 'football', 'Test', ?, ?, ?, 10,
                    NULL, ST_SetSRID(ST_MakePoint(4.4, 51.2), 4326), 'Test Location')
            """, id, status,
            Timestamp.from(startTime), Timestamp.from(endTime));
        return id;
    }
}
