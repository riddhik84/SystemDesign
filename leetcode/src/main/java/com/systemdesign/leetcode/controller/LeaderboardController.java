package com.systemdesign.leetcode.controller;

import com.systemdesign.leetcode.model.LeaderboardEntry;
import com.systemdesign.leetcode.service.LeaderboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/leaderboard")
@RequiredArgsConstructor
public class LeaderboardController {
    private final LeaderboardService leaderboardService;

    @GetMapping("/{competitionId}")
    public ResponseEntity<List<LeaderboardEntry>> getLeaderboard(
            @PathVariable String competitionId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "100") int limit) {
        List<LeaderboardEntry> leaderboard = leaderboardService.getLeaderboard(competitionId, page, limit);
        return ResponseEntity.ok(leaderboard);
    }
}
