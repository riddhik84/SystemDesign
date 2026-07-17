package com.systemdesign.newsfeed.controller;

import com.systemdesign.newsfeed.dto.CreatePostRequest;
import com.systemdesign.newsfeed.dto.PostResponse;
import com.systemdesign.newsfeed.service.PostService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/posts")
public class PostController {

    private final PostService postService;

    public PostController(PostService postService) {
        this.postService = postService;
    }

    /**
     * Create a new post (plain text content + optional media URL).
     */
    @PostMapping
    public ResponseEntity<PostResponse> createPost(@RequestBody CreatePostRequest req) {
        // Validate required fields
        if (req.getAuthorId() == null || req.getAuthorId().isBlank()) {
            throw new IllegalArgumentException("authorId is required");
        }
        if (req.getContent() == null || req.getContent().isBlank()) {
            throw new IllegalArgumentException("content is required");
        }

        PostResponse post = postService.createPost(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(post);
    }

    /**
     * Get a single post by ID.
     */
    @GetMapping("/{postId}")
    public PostResponse getPost(@PathVariable String postId) {
        return postService.getPost(postId);
    }

    /**
     * Like a post. Bumps the like counter (an engagement signal for ranking).
     */
    @PostMapping("/{postId}/like")
    public PostResponse like(@PathVariable String postId) {
        return postService.likePost(postId);
    }

    /**
     * Comment on a post. Bumps the comment counter (an engagement signal for ranking).
     */
    @PostMapping("/{postId}/comment")
    public PostResponse comment(@PathVariable String postId) {
        return postService.commentPost(postId);
    }
}
