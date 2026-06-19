package com.systemdesign.leetcode.service;

import com.systemdesign.leetcode.model.Problem;
import com.systemdesign.leetcode.repository.ProblemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProblemService {
    private final ProblemRepository problemRepository;

    @Transactional(readOnly = true)
    public Page<Problem> getAllProblems(int page, int limit) {
        Pageable pageable = PageRequest.of(page - 1, limit);
        return problemRepository.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public Page<Problem> getProblemsByTags(List<String> tags, int page, int limit) {
        Pageable pageable = PageRequest.of(page - 1, limit);
        return problemRepository.findByTagsIn(tags, pageable);
    }

    @Transactional(readOnly = true)
    public Page<Problem> getProblemsByDifficulty(Problem.DifficultyLevel level, int page, int limit) {
        Pageable pageable = PageRequest.of(page - 1, limit);
        return problemRepository.findByLevel(level, pageable);
    }

    @Transactional(readOnly = true)
    public Problem getProblemById(String id) {
        return problemRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Problem not found with id: " + id));
    }

    @Transactional
    public Problem createProblem(Problem problem) {
        return problemRepository.save(problem);
    }

    @Transactional
    public Problem updateProblem(String id, Problem problem) {
        Problem existing = getProblemById(id);
        existing.setTitle(problem.getTitle());
        existing.setQuestion(problem.getQuestion());
        existing.setLevel(problem.getLevel());
        existing.setTags(problem.getTags());
        existing.setCodeStubs(problem.getCodeStubs());
        return problemRepository.save(existing);
    }

    @Transactional
    public void deleteProblem(String id) {
        problemRepository.deleteById(id);
    }
}
