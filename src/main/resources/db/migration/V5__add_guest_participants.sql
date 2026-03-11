-- Add visibility to sessions for forward-compatibility with private/invite-only sessions.
-- All existing and new sessions default to 'public'.
ALTER TABLE sessions
    ADD COLUMN visibility TEXT NOT NULL DEFAULT 'public',
    ADD CONSTRAINT chk_sessions_visibility
        CHECK (visibility IN ('public', 'private', 'invite_only'));

-- Restructure session_participants to support guest participants (no user account).
--
-- Before: composite PK (session_id, user_id), user_id NOT NULL
-- After:  surrogate UUID PK, user_id nullable, guest_name/guest_token for guests

-- Step 1: Drop the composite primary key
ALTER TABLE session_participants DROP CONSTRAINT session_participants_pkey;

-- Step 2: Add surrogate primary key
ALTER TABLE session_participants
    ADD COLUMN id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE session_participants ADD PRIMARY KEY (id);

-- Step 3: Make user_id nullable (guests have no user account)
ALTER TABLE session_participants ALTER COLUMN user_id DROP NOT NULL;

-- Step 4: Add guest-specific columns
ALTER TABLE session_participants
    ADD COLUMN guest_name  TEXT,
    ADD COLUMN guest_token TEXT;

-- Step 5: Enforce exactly one identity per row:
--   - registered user: user_id NOT NULL
--   - guest:           guest_name AND guest_token NOT NULL
ALTER TABLE session_participants
    ADD CONSTRAINT chk_participant_identity
        CHECK (
            user_id IS NOT NULL
            OR (guest_name IS NOT NULL AND guest_token IS NOT NULL)
        );

-- Step 6: Replace old composite PK with partial unique index (one row per user per session)
DROP INDEX IF EXISTS idx_session_participants_user_id;
CREATE UNIQUE INDEX uq_session_participants_user
    ON session_participants (session_id, user_id)
    WHERE user_id IS NOT NULL;

-- Step 7: One guest_token per session (prevents token reuse across sessions)
CREATE UNIQUE INDEX uq_session_participants_guest_token
    ON session_participants (session_id, guest_token)
    WHERE guest_token IS NOT NULL;

-- Step 8: Fast lookup of guest participants by token
CREATE INDEX idx_session_participants_guest_token
    ON session_participants (guest_token)
    WHERE guest_token IS NOT NULL;
