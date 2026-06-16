package com.systemdesign.gopuff.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.List;

/**
 * Internal request DTO for an availability lookup. Built by the controller from query
 * parameters; consumed by {@code AvailabilityService}.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class AvailabilityRequest {

    private double latitude;
    private double longitude;
    private List<String> itemIds;
    private int page;
}
