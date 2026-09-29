package com.systemdesign.strava.service;

import com.systemdesign.strava.dto.CreateUserRequest;
import com.systemdesign.strava.dto.UserResponse;
import com.systemdesign.strava.model.User;
import com.systemdesign.strava.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.NoSuchElementException;

/**
 * User creation and retrieval service.
 *
 * Responsibilities:
 * - Create users
 * - Retrieve users by id (throwing NoSuchElementException -> 404 if missing)
 * - Resolve display names for other services (feeds, leaderboards) with a graceful fallback
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
     * @return the persisted user as a UserResponse DTO
     */
    @Transactional
    public UserResponse createUser(CreateUserRequest req) {
        User user = new User();
        user.setName(req.getName());
        user.setEmail(req.getEmail());

        User saved = userRepository.save(user);
        log.info("Created user id={} name={}", saved.getId(), saved.getName());
        return toResponse(saved);
    }

    /**
     * Fetch a user by id as a UserResponse DTO.
     *
     * @param id user id
     * @return the UserResponse DTO
     * @throws NoSuchElementException if the user does not exist
     */
    public UserResponse getUser(String id) {
        return toResponse(getUserEntity(id));
    }

    /**
     * Fetch a user entity by id (for internal use by other services).
     *
     * @param id user id
     * @return the User entity
     * @throws NoSuchElementException if the user does not exist
     */
    public User getUserEntity(String id) {
        return userRepository.findById(id)
            .orElseThrow(() -> new NoSuchElementException("User not found: " + id));
    }

    /**
     * Resolve a user's display name, falling back to the raw id if the user no longer exists.
     * Used when building feed/leaderboard responses where a missing user must not break the request.
     *
     * @param userId the user id to resolve
     * @return the user's name, or the id itself if the user is absent
     */
    public String resolveName(String userId) {
        return userRepository.findById(userId)
            .map(User::getName)
            .orElse(userId);
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
        response.setName(user.getName());
        response.setEmail(user.getEmail());
        return response;
    }
}
