package com.systemdesign.strava.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * A batch of buffered GPS points synced from the phone. Batching (rather than
 * streaming every tick) is the core scale decision: it supports offline
 * recording and keeps the server write path lightweight.
 */
@Data
@NoArgsConstructor
public class GpsBatchRequest {
    @NotEmpty
    private List<GpsPointDto> points;
}
