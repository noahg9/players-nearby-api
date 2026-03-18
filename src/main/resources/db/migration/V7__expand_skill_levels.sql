-- Expand skill levels from 3 to 5 options
-- Migrate existing 'advanced' -> 'competitive' (closest equivalent)

UPDATE user_sport_profiles SET skill_level = 'competitive' WHERE skill_level = 'advanced';

ALTER TABLE user_sport_profiles
    DROP CONSTRAINT chk_user_sport_profiles_skill_level;

ALTER TABLE user_sport_profiles
    ADD CONSTRAINT chk_user_sport_profiles_skill_level
        CHECK (skill_level IN ('beginner', 'casual', 'intermediate', 'competitive', 'elite'));
