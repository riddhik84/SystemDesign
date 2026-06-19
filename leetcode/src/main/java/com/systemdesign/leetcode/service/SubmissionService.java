package com.systemdesign.leetcode.service;

import com.systemdesign.leetcode.model.Problem;
import com.systemdesign.leetcode.model.Submission;
import com.systemdesign.leetcode.model.TestCase;
import com.systemdesign.leetcode.repository.SubmissionRepository;
import com.systemdesign.leetcode.repository.TestCaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
@Slf4j
public class SubmissionService {
    private final SubmissionRepository submissionRepository;
    private final TestCaseRepository testCaseRepository;
    private final CodeExecutionService codeExecutionService;
    private final LeaderboardService leaderboardService;
    private final ProblemService problemService;

    @Transactional
    public Submission submitCode(String userId, String problemId, String competitionId, String code, String language) {
        Submission submission = Submission.builder()
                .userId(userId)
                .problemId(problemId)
                .competitionId(competitionId)
                .code(code)
                .language(language)
                .status(Submission.SubmissionStatus.QUEUED)
                .submittedAt(LocalDateTime.now())
                .build();

        submission = submissionRepository.save(submission);

        processSubmissionAsync(submission.getId());

        return submission;
    }

    @Async
    public CompletableFuture<Void> processSubmissionAsync(String submissionId) {
        try {
            Submission submission = submissionRepository.findById(submissionId)
                    .orElseThrow(() -> new RuntimeException("Submission not found"));

            submission.setStatus(Submission.SubmissionStatus.PROCESSING);
            submissionRepository.save(submission);

            List<TestCase> testCases = testCaseRepository.findByProblemId(submission.getProblemId());

            Submission.SubmissionResult result = codeExecutionService.executeCode(
                    submission.getCode(),
                    submission.getLanguage(),
                    testCases,
                    submission
            );

            submission.setResult(result);
            submission.setStatus(Submission.SubmissionStatus.COMPLETED);
            submission.setCompletedAt(LocalDateTime.now());
            submissionRepository.save(submission);

            if (result == Submission.SubmissionResult.ACCEPTED && submission.getCompetitionId() != null) {
                leaderboardService.updateLeaderboard(
                        submission.getCompetitionId(),
                        submission.getUserId(),
                        submission.getCompletedAt()
                );
            }

        } catch (Exception e) {
            log.error("Error processing submission: " + submissionId, e);
            Submission submission = submissionRepository.findById(submissionId).orElse(null);
            if (submission != null) {
                submission.setStatus(Submission.SubmissionStatus.FAILED);
                submission.setErrorMessage(e.getMessage());
                submissionRepository.save(submission);
            }
        }

        return CompletableFuture.completedFuture(null);
    }

    @Transactional(readOnly = true)
    public Submission getSubmissionStatus(String submissionId) {
        return submissionRepository.findById(submissionId)
                .orElseThrow(() -> new RuntimeException("Submission not found with id: " + submissionId));
    }

    @Transactional(readOnly = true)
    public List<Submission> getUserSubmissions(String userId, String problemId) {
        return submissionRepository.findByUserIdAndProblemId(userId, problemId);
    }
}
