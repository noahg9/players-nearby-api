package com.pickupsports.chat.domain;

import java.time.Instant;
import java.util.UUID;

public record ChatMessage(
    UUID id,
    UUID sessionId,
    UUID userId,        // null for guests
    String authorName,  // computed: user.name OR guest_name (never null)
    String content,
    Instant sentAt
) {}
