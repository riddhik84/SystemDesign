package com.systemdesign.yelp.dto;

import lombok.Data;

import java.util.List;

@Data
public class AddBusinessRequest {
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
    private String ownerId;
    private Integer priceRange;
    private List<String> imageUrls;
    private List<String> tags;
}
