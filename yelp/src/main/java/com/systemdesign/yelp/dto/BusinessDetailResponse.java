package com.systemdesign.yelp.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class BusinessDetailResponse {
    private String id;
    private String name;
    private String address;
    private String city;
    private String state;
    private String zipCode;
    private String country;
    private double latitude;
    private double longitude;
    private String category;
    private String description;
    private String phone;
    private String website;
    private double starRating;
    private int reviewCount;
    private Integer priceRange;
    private boolean isOpen;
    private List<String> imageUrls;
    private List<String> tags;
    // Star histogram: {"1": 5, "2": 10, "3": 30, "4": 80, "5": 120}
    private Map<String, Integer> starDistribution;
}
