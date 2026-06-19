package com.systemdesign.leetcode.controller;

import com.systemdesign.leetcode.model.Competition;
import com.systemdesign.leetcode.service.CompetitionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/competitions")
@RequiredArgsConstructor
public class CompetitionController {
    private final CompetitionService competitionService;

    @GetMapping
    public ResponseEntity<List<Competition>> getAllCompetitions() {
        List<Competition> competitions = competitionService.getAllCompetitions();
        return ResponseEntity.ok(competitions);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Competition> getCompetition(@PathVariable String id) {
        Competition competition = competitionService.getCompetitionById(id);
        return ResponseEntity.ok(competition);
    }

    @GetMapping("/active")
    public ResponseEntity<List<Competition>> getActiveCompetitions() {
        List<Competition> competitions = competitionService.getActiveCompetitions();
        return ResponseEntity.ok(competitions);
    }

    @GetMapping("/upcoming")
    public ResponseEntity<List<Competition>> getUpcomingCompetitions() {
        List<Competition> competitions = competitionService.getUpcomingCompetitions();
        return ResponseEntity.ok(competitions);
    }

    @PostMapping
    public ResponseEntity<Competition> createCompetition(@RequestBody Competition competition) {
        Competition created = competitionService.createCompetition(competition);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PostMapping("/{id}/start")
    public ResponseEntity<Competition> startCompetition(@PathVariable String id) {
        Competition competition = competitionService.startCompetition(id);
        return ResponseEntity.ok(competition);
    }

    @PostMapping("/{id}/end")
    public ResponseEntity<Competition> endCompetition(@PathVariable String id) {
        Competition competition = competitionService.endCompetition(id);
        return ResponseEntity.ok(competition);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> cancelCompetition(@PathVariable String id) {
        competitionService.cancelCompetition(id);
        return ResponseEntity.noContent().build();
    }
}
