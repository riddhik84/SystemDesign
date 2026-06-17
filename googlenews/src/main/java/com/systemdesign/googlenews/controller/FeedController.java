package com.systemdesign.googlenews.controller;

import com.systemdesign.googlenews.dto.ArticleDTO;
import com.systemdesign.googlenews.dto.FeedResponse;
import com.systemdesign.googlenews.service.FeedService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Feed", description = "Personalized news feed APIs")
public class FeedController {

    private final FeedService feedService;

    @GetMapping("/feed")
    @Operation(summary = "Get personalized feed",
               description = "Returns personalized news feed based on user interests")
    public ResponseEntity<FeedResponse> getPersonalizedFeed(
            @RequestParam String userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        FeedResponse response = feedService.getPersonalizedFeed(userId, pageable);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/trending")
    @Operation(summary = "Get trending articles",
               description = "Returns globally trending articles")
    public ResponseEntity<FeedResponse> getTrendingFeed(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        FeedResponse response = feedService.getTrendingFeed(pageable);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/articles/{articleId}")
    @Operation(summary = "Get article details",
               description = "Returns full article details")
    public ResponseEntity<ArticleDTO> getArticle(@PathVariable String articleId) {
        return feedService.getArticle(articleId)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }
}
