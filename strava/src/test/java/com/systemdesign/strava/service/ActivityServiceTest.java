package com.systemdesign.strava.service;

import com.systemdesign.strava.dto.ActivityResponse;
import com.systemdesign.strava.dto.StartActivityRequest;
import com.systemdesign.strava.dto.UserResponse;
import com.systemdesign.strava.event.ActivityCompletedEvent;
import com.systemdesign.strava.model.Activity;
import com.systemdesign.strava.model.ActivityStatus;
import com.systemdesign.strava.model.ActivityType;
import com.systemdesign.strava.model.User;
import com.systemdesign.strava.repository.ActivityRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ActivityServiceTest: pure Mockito unit tests for the activity lifecycle state machine
 * (start -> pause/resume -> stop) with all collaborators mocked (no Spring context, no DB,
 * no Redis).
 *
 * Verifies:
 *  - start creates an ACTIVE activity,
 *  - the legal transitions ACTIVE->PAUSED and PAUSED->ACTIVE,
 *  - stop finalizes to COMPLETED, stamps endTime, and publishes {@link ActivityCompletedEvent},
 *  - illegal transitions raise {@link IllegalStateException},
 *  - a missing activity raises {@link NoSuchElementException}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ActivityServiceTest {

    @Mock
    private ActivityRepository activityRepository;

    @Mock
    private UserService userService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private ActivityService activityService;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        User user = new User();
        user.setId("u1");
        user.setName("Alice");
        user.setEmail("alice@example.com");

        UserResponse userResponse = new UserResponse();
        userResponse.setId("u1");
        userResponse.setName("Alice");
        userResponse.setEmail("alice@example.com");

        // The exact user-existence check used internally may vary; stub the plausible
        // collaborator calls leniently so the happy paths do not NPE.
        when(userService.getUser(anyString())).thenReturn(userResponse);
        when(userService.getUserEntity(anyString())).thenReturn(user);
        when(userService.resolveName(anyString())).thenReturn("Alice");
        when(activityRepository.save(any(Activity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void startActivitySetsStatusActive() {
        StartActivityRequest req = new StartActivityRequest();
        req.setUserId("u1");
        req.setType(ActivityType.RUN);
        req.setTitle("Morning Run");

        ActivityResponse response = activityService.startActivity(req);

        assertThat(response.getUserId()).isEqualTo("u1");
        assertThat(response.getType()).isEqualTo(ActivityType.RUN);
        assertThat(response.getStatus()).isEqualTo(ActivityStatus.ACTIVE);
        assertThat(response.getStartTime()).isNotNull();
    }

    @Test
    void pauseActivityMovesActiveToPaused() {
        Activity active = buildActivity("a1", ActivityStatus.ACTIVE);
        when(activityRepository.findById("a1")).thenReturn(Optional.of(active));

        ActivityResponse response = activityService.pauseActivity("a1");

        assertThat(response.getStatus()).isEqualTo(ActivityStatus.PAUSED);
    }

    @Test
    void resumeActivityMovesPausedToActive() {
        Activity paused = buildActivity("a1", ActivityStatus.PAUSED);
        when(activityRepository.findById("a1")).thenReturn(Optional.of(paused));

        ActivityResponse response = activityService.resumeActivity("a1");

        assertThat(response.getStatus()).isEqualTo(ActivityStatus.ACTIVE);
    }

    @Test
    void stopActivityCompletesSetsEndTimeAndPublishesEvent() {
        Activity active = buildActivity("a1", ActivityStatus.ACTIVE);
        when(activityRepository.findById("a1")).thenReturn(Optional.of(active));

        ActivityResponse response = activityService.stopActivity("a1");

        assertThat(response.getStatus()).isEqualTo(ActivityStatus.COMPLETED);
        assertThat(response.getEndTime()).isNotNull();
        verify(eventPublisher, times(1)).publishEvent(any(ActivityCompletedEvent.class));
    }

    @Test
    void pauseAlreadyCompletedActivityThrowsIllegalState() {
        Activity completed = buildActivity("a1", ActivityStatus.COMPLETED);
        when(activityRepository.findById("a1")).thenReturn(Optional.of(completed));

        assertThatThrownBy(() -> activityService.pauseActivity("a1"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void stopAlreadyCompletedActivityThrowsIllegalState() {
        Activity completed = buildActivity("a1", ActivityStatus.COMPLETED);
        when(activityRepository.findById("a1")).thenReturn(Optional.of(completed));

        assertThatThrownBy(() -> activityService.stopActivity("a1"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void getMissingActivityThrowsNoSuchElement() {
        when(activityRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> activityService.getActivity("missing"))
            .isInstanceOf(NoSuchElementException.class);
    }

    private Activity buildActivity(String id, ActivityStatus status) {
        Activity activity = new Activity();
        activity.setId(id);
        activity.setUserId("u1");
        activity.setType(ActivityType.RUN);
        activity.setStatus(status);
        activity.setTitle("Test Activity");
        activity.setStartTime(LocalDateTime.now().minusMinutes(30));
        return activity;
    }
}
