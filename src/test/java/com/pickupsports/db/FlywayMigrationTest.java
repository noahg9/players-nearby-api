package com.pickupsports.db;

import com.pickupsports.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class FlywayMigrationTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    // --- Table existence ---

    @ParameterizedTest
    @ValueSource(strings = {"users", "user_sport_profiles", "sessions", "session_participants"})
    void tableExists(String tableName) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = ?",
            Integer.class, tableName);
        assertThat(count).isEqualTo(1);
    }

    // --- Critical column existence ---

    @ParameterizedTest
    @CsvSource({
        "users, id",
        "users, email",
        "users, name",
        "users, created_at",
        "user_sport_profiles, user_id",
        "user_sport_profiles, sport",
        "user_sport_profiles, skill_level",
        "sessions, id",
        "sessions, location",
        "sessions, host_user_id",
        "sessions, status",
        "sessions, start_time",
        "sessions, end_time",
        "sessions, capacity",
        "session_participants, session_id",
        "session_participants, user_id",
        "session_participants, status",
        "session_participants, joined_at"
    })
    void columnExists(String tableName, String columnName) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
            Integer.class, tableName, columnName);
        assertThat(count)
            .as("Expected column '%s' to exist on table '%s'", columnName, tableName)
            .isEqualTo(1);
    }

    // --- PostGIS geometry type on sessions.location ---

    @Test
    void sessionLocationIsGeometryType() {
        String udtName = jdbcTemplate.queryForObject(
            "SELECT udt_name FROM information_schema.columns " +
            "WHERE table_schema = 'public' AND table_name = 'sessions' AND column_name = 'location'",
            String.class);
        assertThat(udtName).isEqualTo("geometry");
    }

    // --- Index existence ---

    @ParameterizedTest
    @ValueSource(strings = {
        "idx_sessions_location",
        "idx_sessions_start_time",
        "idx_sessions_status",
        "idx_sessions_host_user_id",
        "idx_session_participants_session_id",
        // V5: idx_session_participants_user_id replaced by partial unique index
        "uq_session_participants_user",
        "uq_session_participants_guest_token",
        "idx_session_participants_guest_token"
    })
    void indexExists(String indexName) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?",
            Integer.class, indexName);
        assertThat(count)
            .as("Expected index '%s' to exist", indexName)
            .isEqualTo(1);
    }

    // --- CHECK constraint existence ---

    @ParameterizedTest
    @ValueSource(strings = {
        "chk_sessions_status",
        "chk_sessions_end_after_start",
        "chk_sessions_capacity_positive",
        "chk_session_participants_status",
        "chk_user_sport_profiles_skill_level"
    })
    void checkConstraintExists(String constraintName) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.table_constraints " +
            "WHERE constraint_schema = 'public' AND constraint_name = ? AND constraint_type = 'CHECK'",
            Integer.class, constraintName);
        assertThat(count)
            .as("Expected CHECK constraint '%s' to exist", constraintName)
            .isEqualTo(1);
    }

    // --- V6: sessions.host_user_id is nullable with ON DELETE SET NULL FK ---

    @Test
    void sessionsHostUserIdIsNullable() {
        String isNullable = jdbcTemplate.queryForObject(
            "SELECT is_nullable FROM information_schema.columns " +
            "WHERE table_schema = 'public' AND table_name = 'sessions' AND column_name = 'host_user_id'",
            String.class);
        assertThat(isNullable).isEqualTo("YES");
    }

    @Test
    void sessionsHostUserIdFkConstraintExists() {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.referential_constraints " +
            "WHERE constraint_schema = 'public' AND constraint_name = 'fk_sessions_host_user_id'",
            Integer.class);
        assertThat(count)
            .as("Expected FK constraint 'fk_sessions_host_user_id' to exist")
            .isEqualTo(1);
    }

    // --- PostGIS extension ---

    @Test
    void postGisExtensionEnabled() {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM pg_extension WHERE extname = 'postgis'",
            Integer.class);
        assertThat(count).isEqualTo(1);
    }
}
