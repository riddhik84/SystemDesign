package com.systemdesign.instagram.dto;

import lombok.Data;

import java.util.List;

@Data
public class PostResponse {
    private String id;
    private String authorId;
    private String authorUsername;
    private String caption;
    private String mediaType;
    private List<String> mediaUrls;
    private String createdAt;
}
