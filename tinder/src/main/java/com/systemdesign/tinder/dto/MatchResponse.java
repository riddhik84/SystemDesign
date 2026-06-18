package com.systemdesign.tinder.dto;

import com.systemdesign.tinder.model.Match;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MatchResponse {
    private String matchId;
    private String userId;
    private String matchedUserId;
    private ProfileResponse matchedUser;
    private LocalDateTime createdAt;

    public static MatchResponse fromMatch(Match match, String currentUserId, ProfileResponse otherUser) {
        String matchedUserId = match.getUser1Id().equals(currentUserId)
            ? match.getUser2Id()
            : match.getUser1Id();

        return new MatchResponse(
            match.getId(),
            currentUserId,
            matchedUserId,
            otherUser,
            match.getCreatedAt()
        );
    }
}
