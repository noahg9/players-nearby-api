CREATE TABLE session_messages (
    id          uuid        PRIMARY KEY,
    session_id  uuid        NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    user_id     uuid        REFERENCES users(id) ON DELETE CASCADE,
    guest_name  text,
    content     text        NOT NULL,
    sent_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_session_messages_author
        CHECK (user_id IS NOT NULL OR guest_name IS NOT NULL)
);

CREATE INDEX idx_session_messages_session_sent
    ON session_messages(session_id, sent_at);
