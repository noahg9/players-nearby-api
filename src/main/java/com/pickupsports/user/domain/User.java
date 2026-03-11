package com.pickupsports.user.domain;

import java.time.Instant;
import java.util.UUID;

public record User(UUID id, String email, String name, String bio, Instant createdAt) {}
