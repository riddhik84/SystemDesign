package com.systemdesign.leetcode.controller;

import com.systemdesign.leetcode.model.Submission;
import com.systemdesign.leetcode.service.SubmissionService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class SubmissionController {
    private final SubmissionService submissionService;

    @PostMapping("/problems/{problemId}/submit")
    public ResponseEntity<SubmissionResponse> submitCode(
            @PathVariable String problemId,
            @RequestBody SubmissionRequest request) {
        Submission submission = submissionService.submitCode(
                request.getUserId(),
                problemId,
                request.getCompetitionId(),
                request.getCode(),
                request.getLanguage()
        );

        SubmissionResponse response = new SubmissionResponse();
        response.setSubmissionId(submission.getId());
        response.setStatus(submission.getStatus().name());
        response.setMessage("Submission queued for processing");

        return ResponseEntity.accepted().body(response);
    }

    @GetMapping("/submissions/{id}")
    public ResponseEntity<Submission> getSubmissionStatus(@PathVariable String id) {
        Submission submission = submissionService.getSubmissionStatus(id);
        return ResponseEntity.ok(submission);
    }

    @GetMapping("/users/{userId}/problems/{problemId}/submissions")
    public ResponseEntity<List<Submission>> getUserSubmissions(
            @PathVariable String userId,
            @PathVariable String problemId) {
        List<Submission> submissions = submissionService.getUserSubmissions(userId, problemId);
        return ResponseEntity.ok(submissions);
    }

    @Data
    public static class SubmissionRequest {
        private String userId;
        private String competitionId;
        private String code;
        private String language;
    }

    @Data
    public static class SubmissionResponse {
        private String submissionId;
        private String status;
        private String message;
    }
}
