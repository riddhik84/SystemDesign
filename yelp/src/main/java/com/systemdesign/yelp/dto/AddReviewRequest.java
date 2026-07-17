package com.systemdesign.yelp.dto;

import lombok.Data;

import java.util.List;

@Data
public class AddReviewRequest {
    private String businessId;
    private String userId;
    private int stars;
    private String text;
    private List<String> imageUrls;
}
