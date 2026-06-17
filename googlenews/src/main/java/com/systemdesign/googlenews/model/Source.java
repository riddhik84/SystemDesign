package com.systemdesign.googlenews.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "sources", indexes = {
    @Index(name = "idx_next_crawl", columnList = "is_active,next_crawl_time")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Source {

    @Id
    private String id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, unique = true, length = 2000)
    private String feedUrl;

    @Column(nullable = false, length = 200)
    private String domain;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FeedType feedType;

    @Column(nullable = false)
    @Builder.Default
    private Integer crawlIntervalMinutes = 10;

    private Instant lastCrawledAt;

    private Instant nextCrawlTime;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(nullable = false)
    @Builder.Default
    private Integer trustScore = 50;

    @Builder.Default
    @Column(nullable = false)
    private Integer consecutiveFailures = 0;

    public enum FeedType {
        RSS,
        ATOM
    }
}
