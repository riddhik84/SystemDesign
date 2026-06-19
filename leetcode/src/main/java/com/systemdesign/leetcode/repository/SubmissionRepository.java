package com.systemdesign.leetcode.repository;

import com.systemdesign.leetcode.model.Submission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SubmissionRepository extends JpaRepository<Submission, String> {
    List<Submission> findByUserIdAndProblemId(String userId, String problemId);

    List<Submission> findByCompetitionId(String competitionId);

    @Query("SELECT s FROM Submission s WHERE s.competitionId = :competitionId AND s.result = 'ACCEPTED' ORDER BY s.completedAt")
    List<Submission> findAcceptedSubmissionsByCompetition(@Param("competitionId") String competitionId);

    @Query("SELECT COUNT(DISTINCT s.problemId) FROM Submission s WHERE s.userId = :userId AND s.competitionId = :competitionId AND s.result = 'ACCEPTED'")
    Integer countSolvedProblemsByUserInCompetition(@Param("userId") String userId, @Param("competitionId") String competitionId);
}
