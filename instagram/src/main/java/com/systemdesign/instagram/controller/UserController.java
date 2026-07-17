package com.systemdesign.instagram.controller;

import com.systemdesign.instagram.dto.CreateUserRequest;
import com.systemdesign.instagram.dto.UserResponse;
import com.systemdesign.instagram.service.FollowService;
import com.systemdesign.instagram.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;
    private final FollowService followService;

    public UserController(UserService userService, FollowService followService) {
        this.userService = userService;
        this.followService = followService;
    }

    /**
     * Create a new user.
     */
    @PostMapping
    public ResponseEntity<UserResponse> createUser(@RequestBody CreateUserRequest req) {
        // Validate required fields
        if (req.getUsername() == null || req.getUsername().isBlank()) {
            throw new IllegalArgumentException("Username is required");
        }
        if (req.getDisplayName() == null || req.getDisplayName().isBlank()) {
            throw new IllegalArgumentException("Display name is required");
        }
        if (req.getEmail() == null || req.getEmail().isBlank()) {
            throw new IllegalArgumentException("Email is required");
        }

        UserResponse user = userService.getUserResponse(
            userService.createUser(req).getId()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(user);
    }

    /**
     * Get user details.
     */
    @GetMapping("/{userId}")
    public UserResponse getUser(@PathVariable String userId) {
        return userService.getUserResponse(userId);
    }

    /**
     * Follow a user.
     */
    @PostMapping("/{userId}/follow")
    public ResponseEntity<Void> follow(
        @PathVariable String userId,
        @RequestParam String targetId
    ) {
        if (targetId == null || targetId.isBlank()) {
            throw new IllegalArgumentException("targetId must not be blank");
        }
        followService.follow(userId, targetId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Unfollow a user.
     * Unfollow operation is idempotent - returns 204 No Content whether the follow relationship existed or not.
     */
    @DeleteMapping("/{userId}/follow")
    public ResponseEntity<Void> unfollow(
        @PathVariable String userId,
        @RequestParam String targetId
    ) {
        if (targetId == null || targetId.isBlank()) {
            throw new IllegalArgumentException("targetId must not be blank");
        }
        followService.unfollow(userId, targetId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Get list of follower user IDs.
     */
    @GetMapping("/{userId}/followers")
    public List<String> getFollowers(@PathVariable String userId) {
        return followService.getFollowerIds(userId);
    }

    /**
     * Get list of followee user IDs.
     */
    @GetMapping("/{userId}/following")
    public List<String> getFollowing(@PathVariable String userId) {
        return followService.getFolloweeIds(userId);
    }
}
