package com.systemdesign.leetcode.repository;

import com.systemdesign.leetcode.model.Competition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface CompetitionRepository extends JpaRepository<Competition, String> {
    List<Competition> findByStatus(Competition.CompetitionStatus status);

    List<Competition> findByStartTimeBetween(LocalDateTime start, LocalDateTime end);
}
