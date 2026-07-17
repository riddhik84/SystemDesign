package com.systemdesign.newsfeed.dto;

import lombok.Data;

@Data
public class UserResponse {
    private String id;
    private String username;
    private String displayName;
    private String bio;
    private String profilePictureUrl;
    private long followerCount;
    private long followingCount;
    private long postCount;
    private boolean celebrity;
}
