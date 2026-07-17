package com.systemdesign.instagram.controller;

import com.systemdesign.instagram.dto.CreatePostRequest;
import com.systemdesign.instagram.dto.PostResponse;
import com.systemdesign.instagram.service.PostService;
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
     * Create a new post (photo/video/carousel + caption).
     * Media must be uploaded and completed before creating the post.
     */
    @PostMapping
    public ResponseEntity<PostResponse> createPost(@RequestBody CreatePostRequest req) {
        // Validate required fields
        if (req.getAuthorId() == null || req.getAuthorId().isBlank()) {
            throw new IllegalArgumentException("authorId is required");
        }
        if (req.getMediaType() == null) {
            throw new IllegalArgumentException("mediaType is required");
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
}
