package com.pickupsports.sports.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/sports")
public class SportsController {

    private static final List<String> KNOWN_SPORTS = List.of(
        "football", "basketball", "tennis", "volleyball",
        "padel", "rugby", "hockey", "badminton"
    );

    record SportsResponse(List<String> sports) {}

    @GetMapping
    public ResponseEntity<SportsResponse> getSports() {
        return ResponseEntity.ok(new SportsResponse(KNOWN_SPORTS));
    }
}
