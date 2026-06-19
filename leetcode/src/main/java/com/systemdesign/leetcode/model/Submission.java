package com.systemdesign.leetcode.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "submissions", indexes = {
    @Index(name = "idx_user_problem", columnList = "userId,problemId"),
    @Index(name = "idx_competition", columnList = "competitionId")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Submission {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String userId;

    @Column(nullable = false)
    private String problemId;

    private String competitionId;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String code;

    @Column(nullable = false)
    private String language;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SubmissionStatus status;

    @Enumerated(EnumType.STRING)
    private SubmissionResult result;

    @Column(columnDefinition = "TEXT")
    private String output;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    private Integer testCasesPassed;
    private Integer totalTestCases;
    private Long executionTimeMs;

    @Column(nullable = false)
    private LocalDateTime submittedAt;

    private LocalDateTime completedAt;

    public enum SubmissionStatus {
        QUEUED, PROCESSING, COMPLETED, FAILED
    }

    public enum SubmissionResult {
        ACCEPTED, WRONG_ANSWER, TIME_LIMIT_EXCEEDED, RUNTIME_ERROR, COMPILATION_ERROR
    }
}
