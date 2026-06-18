package com.systemdesign.tinder.service;

import com.systemdesign.tinder.dto.SwipeResponse;
import com.systemdesign.tinder.exception.ResourceNotFoundException;
import com.systemdesign.tinder.model.Match;
import com.systemdesign.tinder.model.Swipe;
import com.systemdesign.tinder.model.User;
import com.systemdesign.tinder.repository.MatchRepository;
import com.systemdesign.tinder.repository.SwipeRepository;
import com.systemdesign.tinder.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class SwipeService {

    private final SwipeRepository swipeRepository;
    private final MatchRepository matchRepository;
    private final UserRepository userRepository;
    private final MatchDetectionService matchDetectionService;
    private final NotificationService notificationService;

    @Transactional
    @CacheEvict(value = "userFeed", key = "#swiperId + '_*'", allEntries = true)
    public SwipeResponse processSwipe(String swiperId, String targetUserId, Swipe.Direction direction) {
        if (swiperId.equals(targetUserId)) {
            throw new IllegalArgumentException("Cannot swipe on yourself");
        }

        User swiper = userRepository.findById(swiperId)
            .orElseThrow(() -> new ResourceNotFoundException("Swiper not found: " + swiperId));

        User target = userRepository.findById(targetUserId)
            .orElseThrow(() -> new ResourceNotFoundException("Target user not found: " + targetUserId));

        if (swipeRepository.existsBySwiperIdAndTargetUserId(swiperId, targetUserId)) {
            throw new IllegalArgumentException("Already swiped on this user");
        }

        Swipe swipe = new Swipe();
        swipe.setSwiperId(swiperId);
        swipe.setTargetUserId(targetUserId);
        swipe.setDirection(direction);
        swipe = swipeRepository.save(swipe);

        log.info("Swipe recorded: {} -> {} ({})", swiperId, targetUserId, direction);

        if (direction == Swipe.Direction.RIGHT) {
            boolean isMatch = matchDetectionService.recordSwipeAndCheckMatch(swiperId, targetUserId, direction);

            if (isMatch) {
                Match match = createMatch(swiperId, targetUserId);
                log.info("Match created: {} between users {} and {}", match.getId(), swiperId, targetUserId);

                notificationService.sendMatchNotification(swiperId, targetUserId, match.getId());
                notificationService.sendMatchNotification(targetUserId, swiperId, match.getId());

                matchDetectionService.cleanupSwipeData(swiperId, targetUserId);

                return SwipeResponse.withMatch(swipe.getId(), match.getId());
            }
        }

        return SwipeResponse.noMatch(swipe.getId());
    }

    private Match createMatch(String user1Id, String user2Id) {
        String sortedUser1 = user1Id.compareTo(user2Id) < 0 ? user1Id : user2Id;
        String sortedUser2 = user1Id.compareTo(user2Id) < 0 ? user2Id : user1Id;

        Match match = new Match();
        match.setUser1Id(sortedUser1);
        match.setUser2Id(sortedUser2);

        return matchRepository.save(match);
    }
}
