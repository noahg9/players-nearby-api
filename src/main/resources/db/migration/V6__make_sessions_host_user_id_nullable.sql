-- Drop old FK constraint (sessions.host_user_id was NOT NULL with no cascade)
ALTER TABLE sessions DROP CONSTRAINT sessions_host_user_id_fkey;

-- Allow NULL so sessions persist after host account deletion (GDPR: historical record kept)
ALTER TABLE sessions ALTER COLUMN host_user_id DROP NOT NULL;

-- Re-add FK with ON DELETE SET NULL so deleting a user nullifies hosted sessions
ALTER TABLE sessions
    ADD CONSTRAINT fk_sessions_host_user_id
    FOREIGN KEY (host_user_id) REFERENCES users(id) ON DELETE SET NULL;
