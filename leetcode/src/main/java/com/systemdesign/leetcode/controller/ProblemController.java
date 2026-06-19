package com.systemdesign.leetcode.controller;

import com.systemdesign.leetcode.model.Problem;
import com.systemdesign.leetcode.service.ProblemService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/problems")
@RequiredArgsConstructor
public class ProblemController {
    private final ProblemService problemService;

    @GetMapping
    public ResponseEntity<Page<Problem>> getAllProblems(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "100") int limit) {
        Page<Problem> problems = problemService.getAllProblems(page, limit);
        return ResponseEntity.ok(problems);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Problem> getProblem(
            @PathVariable String id,
            @RequestParam(required = false) String language) {
        Problem problem = problemService.getProblemById(id);
        return ResponseEntity.ok(problem);
    }

    @GetMapping("/filter/tags")
    public ResponseEntity<Page<Problem>> getProblemsByTags(
            @RequestParam List<String> tags,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "100") int limit) {
        Page<Problem> problems = problemService.getProblemsByTags(tags, page, limit);
        return ResponseEntity.ok(problems);
    }

    @GetMapping("/filter/difficulty")
    public ResponseEntity<Page<Problem>> getProblemsByDifficulty(
            @RequestParam Problem.DifficultyLevel level,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "100") int limit) {
        Page<Problem> problems = problemService.getProblemsByDifficulty(level, page, limit);
        return ResponseEntity.ok(problems);
    }

    @PostMapping
    public ResponseEntity<Problem> createProblem(@RequestBody Problem problem) {
        Problem created = problemService.createProblem(problem);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Problem> updateProblem(
            @PathVariable String id,
            @RequestBody Problem problem) {
        Problem updated = problemService.updateProblem(id, problem);
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteProblem(@PathVariable String id) {
        problemService.deleteProblem(id);
        return ResponseEntity.noContent().build();
    }
}
