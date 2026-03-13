package com.pickupsports.user;

import com.pickupsports.PostgresTestConfiguration;
import com.pickupsports.auth.service.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class UserProfileIntegrationTest {

    @Autowired
    WebApplicationContext webApplicationContext;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JwtService jwtService;

    MockMvc mockMvc;

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private UUID insertUser(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, name) VALUES (?, ?, ?)",
            id, "test-" + id + "@example.com", name);
        return id;
    }

    private String bearerToken(UUID userId) {
        return "Bearer " + jwtService.generateToken(userId, "test@example.com");
    }

    // ── GET /users/me ────────────────────────────────────────────────────────

    @Test
    void getMe_authenticated_returnsProfile() throws Exception {
        UUID userId = insertUser("Jane Doe");
        jdbc.update(
            "INSERT INTO user_sport_profiles (user_id, sport, skill_level, updated_at) VALUES (?, ?, ?, NOW())",
            userId, "football", "intermediate");

        mockMvc.perform(get("/api/v1/users/me")
                .header("Authorization", bearerToken(userId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(userId.toString()))
            .andExpect(jsonPath("$.email").isNotEmpty())
            .andExpect(jsonPath("$.name").value("Jane Doe"))
            .andExpect(jsonPath("$.sportProfiles[0].sport").value("football"))
            .andExpect(jsonPath("$.sportProfiles[0].skillLevel").value("intermediate"));
    }

    @Test
    void getMe_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void getMe_invalidToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/users/me")
                .header("Authorization", "Bearer not-a-valid-jwt"))
            .andExpect(status().isUnauthorized());
    }

    // ── PUT /users/me ────────────────────────────────────────────────────────

    @Test
    void updateMe_updatesNameAndBio() throws Exception {
        UUID userId = insertUser("Old Name");

        mockMvc.perform(put("/api/v1/users/me")
                .header("Authorization", bearerToken(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"New Name\",\"bio\":\"I play football\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(userId.toString()))
            .andExpect(jsonPath("$.email").isNotEmpty())
            .andExpect(jsonPath("$.name").value("New Name"))
            .andExpect(jsonPath("$.bio").value("I play football"));
    }

    @Test
    void updateMe_partialUpdate_keepsMissingFields() throws Exception {
        UUID userId = insertUser("Original Name");
        jdbc.update("UPDATE users SET bio = ? WHERE id = ?", "Original Bio", userId);

        mockMvc.perform(put("/api/v1/users/me")
                .header("Authorization", bearerToken(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Updated Name\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Updated Name"))
            .andExpect(jsonPath("$.bio").value("Original Bio"));
    }

    @Test
    void updateMe_emptyBody_noOpReturnsExistingData() throws Exception {
        UUID userId = insertUser("Stable Name");
        jdbc.update("UPDATE users SET bio = ? WHERE id = ?", "Stable Bio", userId);

        mockMvc.perform(put("/api/v1/users/me")
                .header("Authorization", bearerToken(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Stable Name"))
            .andExpect(jsonPath("$.bio").value("Stable Bio"));
    }

    @Test
    void updateMe_nameTooLong_returns400() throws Exception {
        UUID userId = insertUser("Test User");
        String longName = "a".repeat(101);

        mockMvc.perform(put("/api/v1/users/me")
                .header("Authorization", bearerToken(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + longName + "\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void updateMe_bioTooLong_returns400() throws Exception {
        UUID userId = insertUser("Test User");
        String longBio = "b".repeat(501);

        mockMvc.perform(put("/api/v1/users/me")
                .header("Authorization", bearerToken(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"bio\":\"" + longBio + "\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void updateMe_noToken_returns401() throws Exception {
        mockMvc.perform(put("/api/v1/users/me")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"New Name\"}"))
            .andExpect(status().isUnauthorized());
    }

    // ── PUT /users/me/sports/{sport} ─────────────────────────────────────────

    @Test
    void upsertSport_newSport_returnsProfile() throws Exception {
        UUID userId = insertUser("Sporty User");

        mockMvc.perform(put("/api/v1/users/me/sports/football")
                .header("Authorization", bearerToken(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"skillLevel\":\"intermediate\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sport").value("football"))
            .andExpect(jsonPath("$.skillLevel").value("intermediate"));
    }

    @Test
    void upsertSport_existingSport_updatesSkillLevel() throws Exception {
        UUID userId = insertUser("Sporty User");
        jdbc.update(
            "INSERT INTO user_sport_profiles (user_id, sport, skill_level, updated_at) VALUES (?, ?, ?, NOW())",
            userId, "basketball", "beginner");

        mockMvc.perform(put("/api/v1/users/me/sports/basketball")
                .header("Authorization", bearerToken(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"skillLevel\":\"advanced\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.skillLevel").value("advanced"));
    }

    @Test
    void upsertSport_invalidSkillLevel_returns400() throws Exception {
        UUID userId = insertUser("Test User");

        mockMvc.perform(put("/api/v1/users/me/sports/football")
                .header("Authorization", bearerToken(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"skillLevel\":\"expert\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void upsertSport_noToken_returns401() throws Exception {
        mockMvc.perform(put("/api/v1/users/me/sports/football")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"skillLevel\":\"beginner\"}"))
            .andExpect(status().isUnauthorized());
    }

    // ── GET /users/{id} ──────────────────────────────────────────────────────

    @Test
    void getPublicProfile_existingUser_returnsPublicData() throws Exception {
        UUID userId = insertUser("Public User");
        jdbc.update("UPDATE users SET bio = ? WHERE id = ?", "Public bio", userId);
        jdbc.update(
            "INSERT INTO user_sport_profiles (user_id, sport, skill_level, updated_at) VALUES (?, ?, ?, NOW())",
            userId, "tennis", "beginner");

        mockMvc.perform(get("/api/v1/users/" + userId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(userId.toString()))
            .andExpect(jsonPath("$.name").value("Public User"))
            .andExpect(jsonPath("$.bio").value("Public bio"))
            .andExpect(jsonPath("$.sportProfiles[0].sport").value("tennis"))
            .andExpect(jsonPath("$.email").doesNotExist());
    }

    @Test
    void getPublicProfile_noAuth_returns200() throws Exception {
        UUID userId = insertUser("No Auth User");

        mockMvc.perform(get("/api/v1/users/" + userId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(userId.toString()));
    }

    @Test
    void getPublicProfile_unknownId_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/users/" + UUID.randomUUID()))
            .andExpect(status().isNotFound());
    }

    // ── GET /sports ──────────────────────────────────────────────────────────

    @Test
    void getSports_returnsKnownSportsList() throws Exception {
        mockMvc.perform(get("/api/v1/sports"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sports").isArray())
            .andExpect(jsonPath("$.sports[0]").value("football"))
            .andExpect(jsonPath("$.sports[1]").value("basketball"))
            .andExpect(jsonPath("$.sports[2]").value("tennis"))
            .andExpect(jsonPath("$.sports[3]").value("volleyball"))
            .andExpect(jsonPath("$.sports[4]").value("padel"))
            .andExpect(jsonPath("$.sports[5]").value("rugby"))
            .andExpect(jsonPath("$.sports[6]").value("hockey"))
            .andExpect(jsonPath("$.sports[7]").value("badminton"));
    }

    @Test
    void getSports_noAuth_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/sports"))
            .andExpect(status().isOk());
    }
}
