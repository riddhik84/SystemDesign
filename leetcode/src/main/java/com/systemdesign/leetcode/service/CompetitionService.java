package com.systemdesign.leetcode.service;

import com.systemdesign.leetcode.model.Competition;
import com.systemdesign.leetcode.repository.CompetitionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CompetitionService {
    private final CompetitionRepository competitionRepository;
    private final LeaderboardService leaderboardService;

    @Transactional(readOnly = true)
    public List<Competition> getAllCompetitions() {
        return competitionRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Competition getCompetitionById(String id) {
        return competitionRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Competition not found with id: " + id));
    }

    @Transactional(readOnly = true)
    public List<Competition> getActiveCompetitions() {
        return competitionRepository.findByStatus(Competition.CompetitionStatus.ACTIVE);
    }

    @Transactional(readOnly = true)
    public List<Competition> getUpcomingCompetitions() {
        return competitionRepository.findByStatus(Competition.CompetitionStatus.UPCOMING);
    }

    @Transactional
    public Competition createCompetition(Competition competition) {
        competition.setStatus(Competition.CompetitionStatus.UPCOMING);
        return competitionRepository.save(competition);
    }

    @Transactional
    public Competition startCompetition(String competitionId) {
        Competition competition = getCompetitionById(competitionId);
        competition.setStatus(Competition.CompetitionStatus.ACTIVE);
        competition.setStartTime(LocalDateTime.now());
        return competitionRepository.save(competition);
    }

    @Transactional
    public Competition endCompetition(String competitionId) {
        Competition competition = getCompetitionById(competitionId);
        competition.setStatus(Competition.CompetitionStatus.COMPLETED);
        competition.setEndTime(LocalDateTime.now());
        return competitionRepository.save(competition);
    }

    @Transactional
    public void cancelCompetition(String competitionId) {
        Competition competition = getCompetitionById(competitionId);
        competition.setStatus(Competition.CompetitionStatus.CANCELLED);
        competitionRepository.save(competition);
        leaderboardService.clearLeaderboard(competitionId);
    }
}
