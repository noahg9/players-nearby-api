package com.pickupsports.session.domain;

import java.time.Instant;
import java.util.UUID;

public record SessionSummary(
    UUID id,
    String sport,
    String title,
    String locationName,
    double lat,
    double lng,
    Instant startTime,
    Instant endTime,
    int capacity,
    int participantCount,
    String status
) {
    public int spotsLeft() {
        return Math.max(0, capacity - participantCount);
    }
}
