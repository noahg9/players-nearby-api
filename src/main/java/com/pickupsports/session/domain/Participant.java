package com.pickupsports.session.domain;

import java.time.Instant;
import java.util.UUID;

public record Participant(
        UUID id,
        UUID sessionId,
        UUID userId,          // null for guests
        String guestName,     // null for registered users
        String guestToken,    // null for registered users; opaque claim token for guests
        String status,
        Instant joinedAt,
        String displayName) { // COALESCE(users.name, guest_name) — resolved by repository JOIN

    public boolean isGuest() {
        return userId == null;
    }
}
