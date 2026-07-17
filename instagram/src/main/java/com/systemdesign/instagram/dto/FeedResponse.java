package com.systemdesign.instagram.dto;

import lombok.Data;

import java.util.List;

@Data
public class FeedResponse {
    private String userId;
    private List<PostResponse> posts;
    private int page;
    private int pageSize;
    private boolean hasMore;
    private long servedAtEpochMs;
    private String feedStrategyNote;
}
