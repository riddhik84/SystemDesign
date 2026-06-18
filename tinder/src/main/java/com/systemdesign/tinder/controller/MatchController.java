package com.systemdesign.tinder.controller;

import com.systemdesign.tinder.dto.MatchResponse;
import com.systemdesign.tinder.service.MatchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/matches")
@RequiredArgsConstructor
@Tag(name = "Matches", description = "Match management endpoints")
public class MatchController {

    private final MatchService matchService;

    @GetMapping("/{userId}")
    @Operation(summary = "Get all matches for a user")
    public ResponseEntity<List<MatchResponse>> getUserMatches(@PathVariable String userId) {
        List<MatchResponse> matches = matchService.getUserMatches(userId);
        return ResponseEntity.ok(matches);
    }
}
