package com.systemdesign.leetcode.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LeaderboardEntry {
    private String userId;
    private String username;
    private Integer rank;
    private Integer problemsSolved;
    private Long totalTimeMs;
    private Double score;
}
