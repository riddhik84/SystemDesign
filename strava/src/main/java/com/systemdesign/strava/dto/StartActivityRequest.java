package com.systemdesign.strava.dto;

import com.systemdesign.strava.model.ActivityType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request to begin recording a new activity.
 */
@Data
@NoArgsConstructor
public class StartActivityRequest {
    @NotBlank
    private String userId;
    @NotNull
    private ActivityType type;
    private String title;
}
