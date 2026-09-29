package com.systemdesign.strava.controller;

import com.systemdesign.strava.dto.ActivityResponse;
import com.systemdesign.strava.dto.ActivityStatsResponse;
import com.systemdesign.strava.dto.GpsBatchRequest;
import com.systemdesign.strava.dto.GpsPointDto;
import com.systemdesign.strava.dto.LiveLocationResponse;
import com.systemdesign.strava.dto.StartActivityRequest;
import com.systemdesign.strava.service.ActivityService;
import com.systemdesign.strava.service.GpsIngestionService;
import com.systemdesign.strava.service.LiveActivityService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST endpoints for the activity lifecycle and GPS ingestion.
 *
 * <p>The GPS ingestion endpoint reflects the core design idea: the phone is the source of
 * truth while recording. It buffers GPS points offline and syncs them to the server in
 * idempotent, sequence-ordered batches. The server appends points and maintains running
 * aggregate stats, keeping the write path lightweight enough to scale to millions of
 * concurrent activities.
 */
@RestController
@RequestMapping("/api")
public class ActivityController {

    private final ActivityService activityService;
    private final GpsIngestionService gpsIngestionService;
    private final LiveActivityService liveActivityService;

    public ActivityController(ActivityService activityService,
                              GpsIngestionService gpsIngestionService,
                              LiveActivityService liveActivityService) {
        this.activityService = activityService;
        this.gpsIngestionService = gpsIngestionService;
        this.liveActivityService = liveActivityService;
    }

    /**
     * Start a new activity (status = ACTIVE).
     */
    @PostMapping("/activities")
    public ResponseEntity<ActivityResponse> startActivity(@Valid @RequestBody StartActivityRequest req) {
        ActivityResponse activity = activityService.startActivity(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(activity);
    }

    /**
     * Pause an active activity (ACTIVE -> PAUSED).
     */
    @PostMapping("/activities/{id}/pause")
    public ActivityResponse pauseActivity(@PathVariable String id) {
        return activityService.pauseActivity(id);
    }

    /**
     * Resume a paused activity (PAUSED -> ACTIVE).
     */
    @PostMapping("/activities/{id}/resume")
    public ActivityResponse resumeActivity(@PathVariable String id) {
        return activityService.resumeActivity(id);
    }

    /**
     * Stop and finalize an activity (ACTIVE|PAUSED -> COMPLETED).
     */
    @PostMapping("/activities/{id}/stop")
    public ActivityResponse stopActivity(@PathVariable String id) {
        return activityService.stopActivity(id);
    }

    /**
     * Ingest a batch of buffered GPS points. Idempotent and ordered by client-assigned
     * monotonic sequence — re-submitting already-applied points is a no-op.
     */
    @PostMapping("/activities/{id}/points")
    public ActivityStatsResponse ingestPoints(@PathVariable String id,
                                              @Valid @RequestBody GpsBatchRequest batch) {
        return gpsIngestionService.ingestBatch(id, batch);
    }

    /**
     * Get a single activity with its current aggregate stats.
     */
    @GetMapping("/activities/{id}")
    public ActivityResponse getActivity(@PathVariable String id) {
        return activityService.getActivity(id);
    }

    /**
     * Get the recorded route (GPS points ordered by sequence).
     */
    @GetMapping("/activities/{id}/route")
    public List<GpsPointDto> getRoute(@PathVariable String id) {
        return activityService.getRoute(id);
    }

    /**
     * Get the live location snapshot for an activity.
     */
    @GetMapping("/activities/{id}/live")
    public LiveLocationResponse getLive(@PathVariable String id) {
        return liveActivityService.getLiveLocation(id);
    }

    /**
     * List a user's activities, most recent first.
     */
    @GetMapping("/users/{userId}/activities")
    public List<ActivityResponse> listActivities(@PathVariable String userId,
                                                 @RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "20") int size) {
        return activityService.listActivities(userId, page, size);
    }
}
