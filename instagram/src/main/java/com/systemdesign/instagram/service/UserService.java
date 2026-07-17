package com.systemdesign.instagram.service;

import com.systemdesign.instagram.dto.CreateUserRequest;
import com.systemdesign.instagram.dto.UserResponse;
import com.systemdesign.instagram.model.User;
import com.systemdesign.instagram.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.NoSuchElementException;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional
    public User createUser(CreateUserRequest req) {
        // Check for duplicate username
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

    public User getUser(String id) {
        return userRepository.findById(id)
            .orElseThrow(() -> new NoSuchElementException("User not found: " + id));
    }

    public UserResponse getUserResponse(String id) {
        User user = getUser(id);
        return toResponse(user);
    }

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
