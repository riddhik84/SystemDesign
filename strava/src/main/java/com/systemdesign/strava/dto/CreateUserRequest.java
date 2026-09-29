package com.systemdesign.strava.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload to register a new athlete.
 */
@Data
@NoArgsConstructor
public class CreateUserRequest {
    @NotBlank
    private String name;
    private String email;
}
