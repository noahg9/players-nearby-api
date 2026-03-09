-- Enforce valid enum-like values at the database level
ALTER TABLE sessions
    ADD CONSTRAINT chk_sessions_status
        CHECK (status IN ('active', 'cancelled', 'completed')),
    ADD CONSTRAINT chk_sessions_end_after_start
        CHECK (end_time > start_time),
    ADD CONSTRAINT chk_sessions_capacity_positive
        CHECK (capacity >= 1);

ALTER TABLE session_participants
    ADD CONSTRAINT chk_session_participants_status
        CHECK (status IN ('joined', 'waitlist', 'left'));

ALTER TABLE user_sport_profiles
    ADD CONSTRAINT chk_user_sport_profiles_skill_level
        CHECK (skill_level IN ('beginner', 'intermediate', 'advanced'));
