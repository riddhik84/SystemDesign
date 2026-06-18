package com.systemdesign.tinder.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SwipeResponse {
    private String swipeId;
    private boolean matched;
    private String matchId;
    private String message;

    public static SwipeResponse noMatch(String swipeId) {
        return new SwipeResponse(swipeId, false, null, "Swipe recorded");
    }

    public static SwipeResponse withMatch(String swipeId, String matchId) {
        return new SwipeResponse(swipeId, true, matchId, "It's a match!");
    }
}
