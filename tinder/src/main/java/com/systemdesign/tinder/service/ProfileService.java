package com.systemdesign.tinder.service;

import com.systemdesign.tinder.dto.ProfileRequest;
import com.systemdesign.tinder.dto.ProfileResponse;
import com.systemdesign.tinder.exception.ResourceNotFoundException;
import com.systemdesign.tinder.model.User;
import com.systemdesign.tinder.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProfileService {

    private final UserRepository userRepository;

    @Transactional
    public ProfileResponse createProfile(ProfileRequest request) {
        User user = new User();
        user.setName(request.getName());
        user.setAge(request.getAge());
        user.setGender(request.getGender());
        user.setInterestedIn(request.getInterestedIn());
        user.setAgeMin(request.getAgeMin());
        user.setAgeMax(request.getAgeMax());
        user.setMaxDistance(request.getMaxDistance());
        user.setLatitude(request.getLatitude());
        user.setLongitude(request.getLongitude());
        user.setBio(request.getBio());

        user = userRepository.save(user);
        log.info("Created profile for user: {}", user.getId());

        return ProfileResponse.fromUser(user);
    }

    @Transactional
    @CacheEvict(value = "userProfile", key = "#userId")
    public ProfileResponse updateProfile(String userId, ProfileRequest request) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        user.setName(request.getName());
        user.setAge(request.getAge());
        user.setGender(request.getGender());
        user.setInterestedIn(request.getInterestedIn());
        user.setAgeMin(request.getAgeMin());
        user.setAgeMax(request.getAgeMax());
        user.setMaxDistance(request.getMaxDistance());
        user.setLatitude(request.getLatitude());
        user.setLongitude(request.getLongitude());
        user.setBio(request.getBio());

        user = userRepository.save(user);
        log.info("Updated profile for user: {}", userId);

        return ProfileResponse.fromUser(user);
    }

    @Cacheable(value = "userProfile", key = "#userId")
    public ProfileResponse getProfile(String userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        return ProfileResponse.fromUser(user);
    }

    @Transactional(readOnly = true)
    public User getUserEntity(String userId) {
        return userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
    }
}
