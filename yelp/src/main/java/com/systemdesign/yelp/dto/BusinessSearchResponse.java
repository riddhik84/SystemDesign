package com.systemdesign.yelp.dto;

import lombok.Data;

import java.util.List;

@Data
public class BusinessSearchResponse {
    private List<BusinessSummary> businesses;
    private int total;
    private int page;
    private int pageSize;
    private double searchLatitude;
    private double searchLongitude;
    private double radiusKm;

    @Data
    public static class BusinessSummary {
        private String id;
        private String name;
        private String address;
        private String city;
        private String state;
        private String category;
        private double starRating;
        private int reviewCount;
        private Integer priceRange;
        private boolean isOpen;
        private double distanceKm;
        private String thumbnailUrl;
        // Precomputed formatted strings for UI convenience
        private String starDisplay;   // e.g. "4.2 stars"
        private String priceDisplay;  // e.g. "$$"
    }
}
