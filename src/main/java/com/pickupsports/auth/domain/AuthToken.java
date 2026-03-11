package com.pickupsports.auth.domain;

import java.time.Instant;
import java.util.UUID;

public record AuthToken(UUID id, String token, String email,
                        Instant expiresAt, Instant usedAt, Instant createdAt) {}
