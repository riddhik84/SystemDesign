package com.systemdesign.tinder.dto;

import com.systemdesign.tinder.model.User;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProfileRequest {
    @NotBlank(message = "Name is required")
    private String name;

    @NotNull(message = "Age is required")
    @Min(value = 18, message = "Age must be at least 18")
    @Max(value = 100, message = "Age must be at most 100")
    private Integer age;

    @NotNull(message = "Gender is required")
    private User.Gender gender;

    @NotNull(message = "Interested in is required")
    private User.Gender interestedIn;

    @NotNull(message = "Minimum age preference is required")
    @Min(value = 18, message = "Minimum age must be at least 18")
    private Integer ageMin;

    @NotNull(message = "Maximum age preference is required")
    @Max(value = 100, message = "Maximum age must be at most 100")
    private Integer ageMax;

    @NotNull(message = "Maximum distance is required")
    @Min(value = 1, message = "Distance must be at least 1 km")
    @Max(value = 100, message = "Distance must be at most 100 km")
    private Integer maxDistance;

    @NotNull(message = "Latitude is required")
    @DecimalMin(value = "-90.0", message = "Latitude must be between -90 and 90")
    @DecimalMax(value = "90.0", message = "Latitude must be between -90 and 90")
    private Double latitude;

    @NotNull(message = "Longitude is required")
    @DecimalMin(value = "-180.0", message = "Longitude must be between -180 and 180")
    @DecimalMax(value = "180.0", message = "Longitude must be between -180 and 180")
    private Double longitude;

    @Size(max = 500, message = "Bio must be at most 500 characters")
    private String bio;
}
