package com.systemdesign.yelp.dto;

import lombok.Data;

@Data
public class BusinessSearchRequest {
    private double latitude;
    private double longitude;
    private String query;          // full-text: name, category, tags
    private String category;       // optional exact-match filter
    private Double radiusKm;       // default 5km, max 40km
    private Integer minStars;      // 1-5, optional
    private Integer maxPriceRange; // 1-4, optional
    private Boolean openNow;       // optional
    private String sortBy;         // "distance" | "rating" | "review_count" (default: "distance")
    private int page = 0;
    private int pageSize = 20;
}
