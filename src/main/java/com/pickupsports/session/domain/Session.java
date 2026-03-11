package com.pickupsports.session.domain;

import java.time.Instant;
import java.util.UUID;

public record Session(
        UUID id,
        String sport,
        String title,
        String notes,
        String status,
        String visibility,
        Instant startTime,
        Instant endTime,
        int capacity,
        UUID hostUserId,
        String locationName,
        double lat,
        double lng,
        Instant createdAt) {

    public boolean isActive() {
        return "active".equals(status);
    }

    public boolean isPublic() {
        return "public".equals(visibility);
    }
}
