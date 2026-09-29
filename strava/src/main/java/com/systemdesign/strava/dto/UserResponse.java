package com.systemdesign.strava.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Public view of an athlete.
 */
@Data
@NoArgsConstructor
public class UserResponse {
    private String id;
    private String name;
    private String email;
}
