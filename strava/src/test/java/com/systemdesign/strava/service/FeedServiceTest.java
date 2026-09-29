package com.systemdesign.strava.service;

import com.systemdesign.strava.cache.LiveLocationCacheService;
import com.systemdesign.strava.dto.ActivityResponse;
import com.systemdesign.strava.model.Activity;
import com.systemdesign.strava.model.ActivityStatus;
import com.systemdesign.strava.model.ActivityType;
import com.systemdesign.strava.repository.ActivityRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FeedServiceTest: pure Mockito unit tests for the friend-activity feed.
 *
 * Verifies that:
 *  - a user's friends' COMPLETED activities are returned,
 *  - a user with no friends gets an empty feed without touching the activity store,
 *  - the activity query is scoped to exactly the resolved friend ids.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FeedServiceTest {

    @Mock
    private FriendshipService friendshipService;

    @Mock
    private ActivityRepository activityRepository;

    @Mock
    private ActivityService activityService;

    @Mock
    private LiveLocationCacheService liveLocationCacheService;

    @InjectMocks
    private FeedService feedService;

    @Test
    void returnsFriendsCompletedActivities() {
        when(friendshipService.getFriendIds("u1")).thenReturn(List.of("f1", "f2"));

        Activity a1 = activity("act1", "f1");
        when(activityRepository.findByUserIdInAndStatusOrderByStartTimeDesc(
            eq(List.of("f1", "f2")), eq(ActivityStatus.COMPLETED), any(Pageable.class)))
            .thenReturn(List.of(a1));
        when(activityService.toResponse(a1)).thenReturn(response("act1", "f1"));

        List<ActivityResponse> feed = feedService.getFriendFeed("u1", 0, 20);

        assertThat(feed).hasSize(1);
        assertThat(feed.get(0).getId()).isEqualTo("act1");
        assertThat(feed.get(0).getUserId()).isEqualTo("f1");
    }

    @Test
    void userWithNoFriendsGetsEmptyFeed() {
        when(friendshipService.getFriendIds("loner")).thenReturn(List.of());

        List<ActivityResponse> feed = feedService.getFriendFeed("loner", 0, 20);

        assertThat(feed).isEmpty();
        verify(activityRepository, never()).findByUserIdInAndStatusOrderByStartTimeDesc(
            any(), any(), any());
    }

    @Test
    void queriesOnlyResolvedFriendIds() {
        when(friendshipService.getFriendIds("u1")).thenReturn(List.of("f1", "f2"));
        when(activityRepository.findByUserIdInAndStatusOrderByStartTimeDesc(
            any(), any(), any(Pageable.class))).thenReturn(List.of());

        feedService.getFriendFeed("u1", 0, 20);

        verify(activityRepository).findByUserIdInAndStatusOrderByStartTimeDesc(
            eq(List.of("f1", "f2")), eq(ActivityStatus.COMPLETED), any(Pageable.class));
    }

    private Activity activity(String id, String userId) {
        Activity a = new Activity();
        a.setId(id);
        a.setUserId(userId);
        a.setType(ActivityType.RIDE);
        a.setStatus(ActivityStatus.COMPLETED);
        a.setStartTime(LocalDateTime.now().minusHours(1));
        return a;
    }

    private ActivityResponse response(String id, String userId) {
        ActivityResponse r = new ActivityResponse();
        r.setId(id);
        r.setUserId(userId);
        r.setStatus(ActivityStatus.COMPLETED);
        return r;
    }
}
