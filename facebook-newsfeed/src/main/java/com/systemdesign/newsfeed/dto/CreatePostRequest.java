package com.systemdesign.newsfeed.dto;

import lombok.Data;

@Data
public class CreatePostRequest {
    private String authorId;
    private String content;
    private String mediaUrl;
}
