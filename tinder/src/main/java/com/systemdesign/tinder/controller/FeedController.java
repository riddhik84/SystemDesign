package com.systemdesign.tinder.controller;

import com.systemdesign.tinder.dto.ProfileResponse;
import com.systemdesign.tinder.service.FeedService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/feed")
@RequiredArgsConstructor
@Tag(name = "Feed", description = "User feed and discovery endpoints")
public class FeedController {

    private final FeedService feedService;

    @GetMapping("/{userId}")
    @Operation(summary = "Get potential matches feed for a user")
    public ResponseEntity<List<ProfileResponse>> getFeed(
            @PathVariable String userId,
            @RequestParam(required = false, defaultValue = "50") Integer limit) {
        List<ProfileResponse> feed = feedService.getPotentialMatches(userId, limit);
        return ResponseEntity.ok(feed);
    }
}
