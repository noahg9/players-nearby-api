package com.pickupsports.user.domain;

import java.util.UUID;

public record UserSportProfile(UUID userId, String sport, String skillLevel) {}
