package com.systemdesign.leetcode.repository;

import com.systemdesign.leetcode.model.Problem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProblemRepository extends JpaRepository<Problem, String> {
    Page<Problem> findAll(Pageable pageable);

    @Query("SELECT p FROM Problem p JOIN p.tags t WHERE t IN :tags")
    Page<Problem> findByTagsIn(@Param("tags") List<String> tags, Pageable pageable);

    Page<Problem> findByLevel(Problem.DifficultyLevel level, Pageable pageable);
}
