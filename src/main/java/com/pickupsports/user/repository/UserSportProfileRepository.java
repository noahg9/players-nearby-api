package com.pickupsports.user.repository;

import com.pickupsports.user.domain.UserSportProfile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class UserSportProfileRepository {

    private final JdbcTemplate jdbc;

    public UserSportProfileRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<UserSportProfile> findByUserId(UUID userId) {
        return jdbc.query(
            "SELECT user_id, sport, skill_level FROM user_sport_profiles WHERE user_id = ? ORDER BY sport",
            (rs, i) -> new UserSportProfile(
                rs.getObject("user_id", UUID.class),
                rs.getString("sport"),
                rs.getString("skill_level")
            ),
            userId
        );
    }

    public UserSportProfile upsert(UUID userId, String sport, String skillLevel) {
        jdbc.update(
            "INSERT INTO user_sport_profiles (user_id, sport, skill_level, updated_at) " +
            "VALUES (?, ?, ?, NOW()) " +
            "ON CONFLICT (user_id, sport) DO UPDATE SET skill_level = EXCLUDED.skill_level, updated_at = NOW()",
            userId, sport, skillLevel
        );
        return new UserSportProfile(userId, sport, skillLevel);
    }
}
