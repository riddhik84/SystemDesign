package com.systemdesign.strava.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * Event published after an activity is stopped, finalized, and committed to the database.
 *
 * <p>Consumed by the leaderboard subsystem via an {@code @Async @TransactionalEventListener}
 * (AFTER_COMMIT) so that segment matching runs off the request thread and only against
 * durably-persisted route points — avoiding read-before-commit races.
 */
@Getter
public class ActivityCompletedEvent extends ApplicationEvent {

    private final String activityId;
    private final String userId;

    public ActivityCompletedEvent(Object source, String activityId, String userId) {
        super(source);
        this.activityId = activityId;
        this.userId = userId;
    }
}
