package com.systemdesign.tinder.service;

import com.systemdesign.tinder.dto.ProfileResponse;
import com.systemdesign.tinder.model.User;
import com.systemdesign.tinder.repository.SwipeRepository;
import com.systemdesign.tinder.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class FeedService {

    private final UserRepository userRepository;
    private final SwipeRepository swipeRepository;
    private final ProfileService profileService;

    private static final int DEFAULT_FEED_SIZE = 50;

    @Transactional(readOnly = true)
    @Cacheable(value = "userFeed", key = "#userId + '_' + #limit", unless = "#result.isEmpty()")
    public List<ProfileResponse> getPotentialMatches(String userId, Integer limit) {
        User currentUser = profileService.getUserEntity(userId);

        if (limit == null || limit <= 0) {
            limit = DEFAULT_FEED_SIZE;
        }

        List<User> potentialMatches = userRepository.findPotentialMatches(
            userId,
            currentUser.getLatitude(),
            currentUser.getLongitude(),
            currentUser.getMaxDistance(),
            currentUser.getAgeMin(),
            currentUser.getAgeMax(),
            currentUser.getInterestedIn().name(),
            limit
        );

        log.info("Found {} potential matches for user {}", potentialMatches.size(), userId);

        return potentialMatches.stream()
            .map(user -> {
                double distance = calculateDistance(
                    currentUser.getLatitude(),
                    currentUser.getLongitude(),
                    user.getLatitude(),
                    user.getLongitude()
                );
                return ProfileResponse.fromUserWithDistance(user, Math.round(distance * 10.0) / 10.0);
            })
            .collect(Collectors.toList());
    }

    private double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        final int EARTH_RADIUS = 6371;

        double latDistance = Math.toRadians(lat2 - lat1);
        double lonDistance = Math.toRadians(lon2 - lon1);

        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        return EARTH_RADIUS * c;
    }
}
