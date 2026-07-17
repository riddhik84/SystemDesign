package com.systemdesign.instagram.service;

import com.systemdesign.instagram.dto.UploadUrlRequest;
import com.systemdesign.instagram.dto.UploadUrlResponse;
import com.systemdesign.instagram.model.Media;
import com.systemdesign.instagram.model.MediaStatus;
import com.systemdesign.instagram.model.MediaType;
import com.systemdesign.instagram.repository.MediaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

@Service
public class MediaService {

    private static final Logger log = LoggerFactory.getLogger(MediaService.class);

    private final MediaRepository mediaRepository;

    @Value("${app.media.max-photo-bytes}")
    private long maxPhotoBytes;

    @Value("${app.media.max-video-bytes}")
    private long maxVideoBytes;

    @Value("${app.media.cdn-base-url}")
    private String cdnBaseUrl;

    @Value("${app.media.upload-base-url}")
    private String uploadBaseUrl;

    public MediaService(MediaRepository mediaRepository) {
        this.mediaRepository = mediaRepository;
    }

    @Transactional
    public UploadUrlResponse createUploadUrl(UploadUrlRequest req) {
        // Validate size based on media type
        long maxSize = getMaxSizeForType(req.getType());
        if (req.getSizeBytes() > maxSize) {
            throw new IllegalArgumentException(
                String.format("File size %d exceeds maximum %d bytes for type %s",
                    req.getSizeBytes(), maxSize, req.getType())
            );
        }

        // Create media record
        Media media = new Media();
        media.setUploaderId(req.getUploaderId());
        media.setType(req.getType());
        media.setSizeBytes(req.getSizeBytes());
        media.setStatus(MediaStatus.PENDING);

        // Generate placeholder blobKey (will be updated after save)
        String tempBlobKey = req.getUploaderId() + "/temp";
        media.setBlobKey(tempBlobKey);

        // Save to get ID
        Media saved = mediaRepository.save(media);

        // Generate final blob key and URLs with actual ID
        String blobKey = req.getUploaderId() + "/" + saved.getId();
        String uploadUrl = uploadBaseUrl + "/" + blobKey + "?sig=SIMULATED";
        String cdnUrl = cdnBaseUrl + "/" + blobKey;

        // Update media with final blob key and URLs
        saved.setBlobKey(blobKey);
        saved.setUploadUrl(uploadUrl);
        saved.setCdnUrl(cdnUrl);
        mediaRepository.save(saved);

        // Build response
        UploadUrlResponse response = new UploadUrlResponse();
        response.setMediaId(saved.getId());
        response.setUploadUrl(uploadUrl);
        response.setBlobKey(blobKey);
        response.setCdnUrl(cdnUrl);
        response.setMaxSizeBytes(maxSize);

        log.info("Created upload URL for media id={} type={} uploaderId={}",
            saved.getId(), req.getType(), req.getUploaderId());

        return response;
    }

    @Transactional
    public void completeUpload(String mediaId) {
        Media media = mediaRepository.findById(mediaId)
            .orElseThrow(() -> new NoSuchElementException("Media not found: " + mediaId));

        media.setStatus(MediaStatus.UPLOADED);
        mediaRepository.save(media);

        log.info("Completed upload for media id={}", mediaId);
    }

    public Media getMedia(String mediaId) {
        return mediaRepository.findById(mediaId)
            .orElseThrow(() -> new NoSuchElementException("Media not found: " + mediaId));
    }

    public List<String> resolveCdnUrls(List<String> mediaIds) {
        List<String> cdnUrls = new ArrayList<>();

        for (String mediaId : mediaIds) {
            Media media = getMedia(mediaId);

            // Validate that media has been uploaded
            if (media.getStatus() != MediaStatus.UPLOADED) {
                throw new IllegalStateException(
                    "Media not uploaded: " + mediaId + " (status: " + media.getStatus() + ")"
                );
            }

            cdnUrls.add(media.getCdnUrl());
        }

        return cdnUrls;
    }

    private long getMaxSizeForType(MediaType type) {
        if (type == MediaType.PHOTO) {
            return maxPhotoBytes;
        } else {
            // VIDEO and CAROUSEL use video size limit
            return maxVideoBytes;
        }
    }
}
