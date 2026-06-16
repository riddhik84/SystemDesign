package com.systemdesign.gopuff.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * Response for {@code GET /availability}. This object is what gets serialized to JSON and
 * cached in Redis (1-minute TTL), so it must remain a plain, Jackson-friendly bean.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AvailabilityResponse {

    private double latitude;
    private double longitude;
    private int page;
    private int pageSize;
    private int totalItems;
    private List<ItemAvailability> items;
}
