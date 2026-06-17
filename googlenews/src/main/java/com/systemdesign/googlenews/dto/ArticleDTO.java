package com.systemdesign.googlenews.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Set;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ArticleDTO {
    private String id;
    private String title;
    private String summary;
    private String content;
    private String url;
    private String imageUrl;
    private SourceDTO source;
    private Set<String> topics;
    private Instant publishedAt;
    private Integer engagementScore;
    private Double relevanceScore;
}
