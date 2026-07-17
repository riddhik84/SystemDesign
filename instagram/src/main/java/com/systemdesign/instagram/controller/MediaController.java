package com.systemdesign.instagram.controller;

import com.systemdesign.instagram.dto.UploadUrlRequest;
import com.systemdesign.instagram.dto.UploadUrlResponse;
import com.systemdesign.instagram.service.MediaService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/media")
public class MediaController {

    private final MediaService mediaService;

    public MediaController(MediaService mediaService) {
        this.mediaService = mediaService;
    }

    /**
     * Request a signed upload URL for media (photo/video).
     * Client uploads to the returned URL, then calls /complete.
     */
    @PostMapping("/upload-url")
    public UploadUrlResponse createUploadUrl(@RequestBody UploadUrlRequest req) {
        // Validate required fields
        if (req.getUploaderId() == null || req.getUploaderId().isBlank()) {
            throw new IllegalArgumentException("uploaderId is required");
        }
        if (req.getType() == null) {
            throw new IllegalArgumentException("type is required");
        }

        return mediaService.createUploadUrl(req);
    }

    /**
     * Mark media as uploaded and ready for use in posts.
     */
    @PostMapping("/{mediaId}/complete")
    public ResponseEntity<Void> completeUpload(@PathVariable String mediaId) {
        mediaService.completeUpload(mediaId);
        return ResponseEntity.ok().build();
    }
}
