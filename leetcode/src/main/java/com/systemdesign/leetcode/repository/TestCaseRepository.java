package com.systemdesign.leetcode.repository;

import com.systemdesign.leetcode.model.TestCase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TestCaseRepository extends JpaRepository<TestCase, String> {
    List<TestCase> findByProblemId(String problemId);
}
