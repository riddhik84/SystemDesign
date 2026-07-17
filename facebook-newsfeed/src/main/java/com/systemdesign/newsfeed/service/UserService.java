package com.systemdesign.newsfeed.service;

import com.systemdesign.newsfeed.dto.CreateUserRequest;
import com.systemdesign.newsfeed.dto.UserResponse;
import com.systemdesign.newsfeed.model.User;
import com.systemdesign.newsfeed.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.NoSuchElementException;

/**
 * User creation and retrieval service.
 *
 * Responsibilities:
 * - Create users (rejecting duplicate usernames)
 * - Retrieve users by id (throwing NoSuchElementException -> 404 if missing)
 * - Map User entities to UserResponse DTOs
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Create a new user.
     *
     * @param req user creation request
     * @return the persisted User entity
     * @throws IllegalStateException if the username already exists
     */
    @Transactional
    public User createUser(CreateUserRequest req) {
        // Reject duplicate usernames
        if (userRepository.findByUsername(req.getUsername()).isPresent()) {
            throw new IllegalStateException("Username already exists: " + req.getUsername());
        }

        User user = new User();
        user.setUsername(req.getUsername());
        user.setDisplayName(req.getDisplayName());
        user.setEmail(req.getEmail());
        user.setBio(req.getBio());

        User saved = userRepository.save(user);
        log.info("Created user id={} username={}", saved.getId(), saved.getUsername());
        return saved;
    }

    /**
     * Fetch a user by id.
     *
     * @param id user id
     * @return the User entity
     * @throws NoSuchElementException if the user does not exist
     */
    public User getUser(String id) {
        return userRepository.findById(id)
            .orElseThrow(() -> new NoSuchElementException("User not found: " + id));
    }

    /**
     * Fetch a user by id as a UserResponse DTO.
     *
     * @param id user id
     * @return the UserResponse DTO
     * @throws NoSuchElementException if the user does not exist
     */
    public UserResponse getUserResponse(String id) {
        return toResponse(getUser(id));
    }

    /**
     * Map a User entity to its UserResponse DTO.
     *
     * @param user the User entity
     * @return the UserResponse DTO
     */
    public UserResponse toResponse(User user) {
        UserResponse response = new UserResponse();
        response.setId(user.getId());
        response.setUsername(user.getUsername());
        response.setDisplayName(user.getDisplayName());
        response.setBio(user.getBio());
        response.setProfilePictureUrl(user.getProfilePictureUrl());
        response.setFollowerCount(user.getFollowerCount());
        response.setFollowingCount(user.getFollowingCount());
        response.setPostCount(user.getPostCount());
        response.setCelebrity(user.isCelebrity());
        return response;
    }
}
