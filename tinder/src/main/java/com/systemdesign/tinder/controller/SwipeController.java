package com.systemdesign.tinder.controller;

import com.systemdesign.tinder.dto.SwipeRequest;
import com.systemdesign.tinder.dto.SwipeResponse;
import com.systemdesign.tinder.service.SwipeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/swipe")
@RequiredArgsConstructor
@Tag(name = "Swipe", description = "Swipe and matching endpoints")
public class SwipeController {

    private final SwipeService swipeService;

    @PostMapping("/{swiperId}/{targetUserId}")
    @Operation(summary = "Process a swipe on a user")
    public ResponseEntity<SwipeResponse> swipe(
            @PathVariable String swiperId,
            @PathVariable String targetUserId,
            @Valid @RequestBody SwipeRequest request) {
        SwipeResponse response = swipeService.processSwipe(swiperId, targetUserId, request.getDecision());
        return ResponseEntity.ok(response);
    }
}
