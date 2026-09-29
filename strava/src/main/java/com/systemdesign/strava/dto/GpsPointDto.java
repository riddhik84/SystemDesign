package com.systemdesign.strava.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * A single GPS sample captured on the phone. The client assigns a monotonic
 * {@code sequence} so the server can apply batches idempotently and in order.
 */
@Data
@NoArgsConstructor
public class GpsPointDto {
    private long sequence;
    private double latitude;
    private double longitude;
    private double elevationMeters;
    private LocalDateTime recordedAt;
}
