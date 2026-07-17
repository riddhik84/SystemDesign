package com.systemdesign.newsfeed.dto;

import lombok.Data;

@Data
public class PostResponse {
    private String id;
    private String authorId;
    private String authorUsername;
    private String content;
    private String mediaUrl;
    private int likeCount;
    private int commentCount;
    private String createdAt;
    private Double score;
}
