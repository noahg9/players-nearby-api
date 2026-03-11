package com.pickupsports.auth;

import com.pickupsports.PostgresTestConfiguration;
import com.pickupsports.auth.service.EmailService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class AuthControllerIntegrationTest {

    @Autowired
    WebApplicationContext webApplicationContext;

    @MockitoBean
    EmailService emailService;

    @Autowired
    JdbcTemplate jdbc;

    MockMvc mockMvc;

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    private String uniqueEmail() {
        return "test-" + UUID.randomUUID() + "@example.com";
    }

    // ── request-login ──────────────────────────────────────────────────────────

    @Test
    void requestLogin_validEmail_returns200() throws Exception {
        mockMvc.perform(post("/api/v1/auth/request-login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + uniqueEmail() + "\"}"))
            .andExpect(status().isOk());
    }

    @Test
    void requestLogin_invalidEmail_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/request-login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"not-an-email\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void requestLogin_blankEmail_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/request-login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void requestLogin_rateLimitExceeded_returns429() throws Exception {
        String email = uniqueEmail();
        String body = "{\"email\":\"" + email + "\"}";

        // Send 5 requests — all should succeed
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/v1/auth/request-login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(status().isOk());
        }

        // 6th request should be rate-limited
        mockMvc.perform(post("/api/v1/auth/request-login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isTooManyRequests());
    }

    // ── confirm ────────────────────────────────────────────────────────────────

    @Test
    void confirm_validToken_newUser_returns200WithJwtAndUser() throws Exception {
        String email = uniqueEmail();
        String token = insertToken(email, Instant.now().plus(15, ChronoUnit.MINUTES), null);
        String expectedName = email.split("@")[0];

        mockMvc.perform(post("/api/v1/auth/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.token").isNotEmpty())
            .andExpect(jsonPath("$.user.email").value(email))
            .andExpect(jsonPath("$.user.id").isNotEmpty())
            .andExpect(jsonPath("$.user.name").value(expectedName));
    }

    @Test
    void confirm_validToken_existingUser_returnsExistingUser() throws Exception {
        String email = uniqueEmail();
        UUID userId = UUID.randomUUID();
        // Insert user first
        jdbc.update("INSERT INTO users (id, email, name) VALUES (?, ?, ?)", userId, email, "existing-name");

        String token = insertToken(email, Instant.now().plus(15, ChronoUnit.MINUTES), null);

        mockMvc.perform(post("/api/v1/auth/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.user.id").value(userId.toString()))
            .andExpect(jsonPath("$.user.name").value("existing-name"));
    }

    @Test
    void confirm_expiredToken_returns400WithProblemDetail() throws Exception {
        String email = uniqueEmail();
        String token = insertToken(email, Instant.now().minus(1, ChronoUnit.MINUTES), null);

        mockMvc.perform(post("/api/v1/auth/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.detail").value("Token expired"));
    }

    @Test
    void confirm_usedToken_returns400WithProblemDetail() throws Exception {
        String email = uniqueEmail();
        String token = insertToken(email, Instant.now().plus(15, ChronoUnit.MINUTES), Instant.now());

        mockMvc.perform(post("/api/v1/auth/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.detail").value("Token already used"));
    }

    @Test
    void confirm_unknownToken_returns400WithProblemDetail() throws Exception {
        mockMvc.perform(post("/api/v1/auth/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"totally-unknown-token\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.detail").value("Invalid token"));
    }

    @Test
    void confirm_validToken_marksTokenAsUsed() throws Exception {
        String email = uniqueEmail();
        String token = insertToken(email, Instant.now().plus(15, ChronoUnit.MINUTES), null);

        mockMvc.perform(post("/api/v1/auth/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}"))
            .andExpect(status().isOk());

        Instant usedAt = jdbc.queryForObject(
            "SELECT used_at FROM auth_tokens WHERE token = ?",
            (rs, i) -> rs.getTimestamp("used_at").toInstant(),
            token
        );
        assertThat(usedAt).isNotNull();
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private String insertToken(String email, Instant expiresAt, Instant usedAt) {
        String token = UUID.randomUUID().toString();
        jdbc.update(
            "INSERT INTO auth_tokens (id, token, email, expires_at, used_at) VALUES (?, ?, ?, ?, ?)",
            UUID.randomUUID(), token, email,
            Timestamp.from(expiresAt),
            usedAt != null ? Timestamp.from(usedAt) : null
        );
        return token;
    }
}
