package com.systemdesign.googlenews.controller;

import com.systemdesign.googlenews.dto.InterestRequest;
import com.systemdesign.googlenews.model.UserInterest;
import com.systemdesign.googlenews.service.UserInterestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/users/{userId}/interests")
@RequiredArgsConstructor
@Tag(name = "User Interests", description = "User interest management APIs")
public class UserInterestController {

    private final UserInterestService userInterestService;

    @GetMapping
    @Operation(summary = "Get user interests",
               description = "Returns all interests for a user")
    public ResponseEntity<List<UserInterest>> getUserInterests(@PathVariable String userId) {
        List<UserInterest> interests = userInterestService.getUserInterests(userId);
        return ResponseEntity.ok(interests);
    }

    @PostMapping
    @Operation(summary = "Add user interest",
               description = "Adds a new interest (topic, source, or keyword) for the user")
    public ResponseEntity<UserInterest> addInterest(
            @PathVariable String userId,
            @Valid @RequestBody InterestRequest request) {

        UserInterest interest = userInterestService.addInterest(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(interest);
    }

    @DeleteMapping("/{interestId}")
    @Operation(summary = "Remove user interest",
               description = "Removes an interest from the user's profile")
    public ResponseEntity<Void> removeInterest(
            @PathVariable String userId,
            @PathVariable String interestId) {

        userInterestService.removeInterest(userId, interestId);
        return ResponseEntity.noContent().build();
    }
}
