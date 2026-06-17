package com.systemdesign.googlenews.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FeedResponse {
    private List<ArticleDTO> articles;
    private int page;
    private int totalPages;
    private long totalElements;
    private boolean hasMore;
}
