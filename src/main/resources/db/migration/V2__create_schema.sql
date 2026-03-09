CREATE TABLE users (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    email       TEXT        NOT NULL UNIQUE,
    name        TEXT        NOT NULL,
    bio         TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE user_sport_profiles (
    user_id     UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    sport       TEXT        NOT NULL,
    skill_level TEXT        NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, sport)
);

CREATE TABLE sessions (
    id            UUID               PRIMARY KEY DEFAULT gen_random_uuid(),
    sport         TEXT               NOT NULL,
    title         TEXT               NOT NULL,
    notes         TEXT,
    status        TEXT               NOT NULL DEFAULT 'active',
    start_time    TIMESTAMPTZ        NOT NULL,
    end_time      TIMESTAMPTZ        NOT NULL,
    capacity      INTEGER            NOT NULL,
    host_user_id  UUID               NOT NULL REFERENCES users(id),
    location      GEOMETRY(Point, 4326) NOT NULL,
    location_name TEXT               NOT NULL,
    created_at    TIMESTAMPTZ        NOT NULL DEFAULT NOW()
);

CREATE TABLE session_participants (
    session_id  UUID        NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    user_id     UUID        NOT NULL REFERENCES users(id)    ON DELETE CASCADE,
    status      TEXT        NOT NULL,
    joined_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (session_id, user_id)
);

-- Indexes
CREATE INDEX idx_sessions_location          ON sessions             USING GIST (location);
CREATE INDEX idx_sessions_start_time        ON sessions             (start_time);
CREATE INDEX idx_sessions_status            ON sessions             (status);
CREATE INDEX idx_sessions_host_user_id      ON sessions             (host_user_id);
CREATE INDEX idx_session_participants_session_id ON session_participants (session_id);
CREATE INDEX idx_session_participants_user_id    ON session_participants (user_id);
-- users.email UNIQUE constraint already creates a unique B-tree index; no separate index needed
