package com.systemdesign.instagram.service;

import com.systemdesign.instagram.dto.CreateUserRequest;
import com.systemdesign.instagram.dto.UploadUrlRequest;
import com.systemdesign.instagram.dto.UploadUrlResponse;
import com.systemdesign.instagram.model.Media;
import com.systemdesign.instagram.model.MediaStatus;
import com.systemdesign.instagram.model.MediaType;
import com.systemdesign.instagram.model.User;
import com.systemdesign.instagram.repository.MediaRepository;
import com.systemdesign.instagram.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class MediaServiceTest {

    @Autowired
    private MediaService mediaService;

    @Autowired
    private UserService userService;

    @Autowired
    private MediaRepository mediaRepository;

    @Autowired
    private UserRepository userRepository;

    @Value("${app.media.max-photo-bytes:8388608}")
    private long maxPhotoBytes;

    @Value("${app.media.max-video-bytes:4294967296}")
    private long maxVideoBytes;

    @Value("${app.media.cdn-base-url:https://cdn.instagram-clone.example.com}")
    private String cdnBaseUrl;

    @Value("${app.media.upload-base-url:https://uploads.instagram-clone.example.com}")
    private String uploadBaseUrl;

    private User testUser;

    @BeforeEach
    void setUp() {
        mediaRepository.deleteAll();
        userRepository.deleteAll();

        CreateUserRequest req = new CreateUserRequest();
        req.setUsername("mediauser");
        req.setDisplayName("Media User");
        req.setEmail("media@example.com");
        req.setBio("Test user for media uploads");
        testUser = userService.createUser(req);
    }

    @Test
    void createUploadUrlForPhotoWithinLimit() {
        UploadUrlRequest req = new UploadUrlRequest();
        req.setUploaderId(testUser.getId());
        req.setType(MediaType.PHOTO);
        req.setSizeBytes(5_000_000); // 5MB, within 8MB limit
        req.setFileName("photo.jpg");

        UploadUrlResponse response = mediaService.createUploadUrl(req);

        assertThat(response.getMediaId()).isNotNull();
        assertThat(response.getUploadUrl()).startsWith(uploadBaseUrl);
        assertThat(response.getCdnUrl()).startsWith(cdnBaseUrl);
        assertThat(response.getBlobKey()).contains(testUser.getId());
        assertThat(response.getBlobKey()).contains(response.getMediaId());
        assertThat(response.getMaxSizeBytes()).isEqualTo(maxPhotoBytes);

        Media media = mediaRepository.findById(response.getMediaId()).orElseThrow();
        assertThat(media.getStatus()).isEqualTo(MediaStatus.PENDING);
        assertThat(media.getType()).isEqualTo(MediaType.PHOTO);
        assertThat(media.getSizeBytes()).isEqualTo(5_000_000);
    }

    @Test
    void createUploadUrlForVideoWithinLimit() {
        UploadUrlRequest req = new UploadUrlRequest();
        req.setUploaderId(testUser.getId());
        req.setType(MediaType.VIDEO);
        req.setSizeBytes(100_000_000); // 100MB, within 4GB limit
        req.setFileName("video.mp4");

        UploadUrlResponse response = mediaService.createUploadUrl(req);

        assertThat(response.getMediaId()).isNotNull();
        assertThat(response.getUploadUrl()).contains(response.getBlobKey());
        assertThat(response.getMaxSizeBytes()).isEqualTo(maxVideoBytes);

        Media media = mediaRepository.findById(response.getMediaId()).orElseThrow();
        assertThat(media.getStatus()).isEqualTo(MediaStatus.PENDING);
        assertThat(media.getType()).isEqualTo(MediaType.VIDEO);
    }

    @Test
    void createUploadUrlForCarouselWithinLimit() {
        UploadUrlRequest req = new UploadUrlRequest();
        req.setUploaderId(testUser.getId());
        req.setType(MediaType.CAROUSEL);
        req.setSizeBytes(500_000_000); // 500MB, within 4GB limit
        req.setFileName("carousel.mp4");

        UploadUrlResponse response = mediaService.createUploadUrl(req);

        assertThat(response.getMediaId()).isNotNull();
        assertThat(response.getMaxSizeBytes()).isEqualTo(maxVideoBytes);

        Media media = mediaRepository.findById(response.getMediaId()).orElseThrow();
        assertThat(media.getType()).isEqualTo(MediaType.CAROUSEL);
    }

    @Test
    void photoExceedingMaxSizeThrowsIllegalArgumentException() {
        UploadUrlRequest req = new UploadUrlRequest();
        req.setUploaderId(testUser.getId());
        req.setType(MediaType.PHOTO);
        req.setSizeBytes(maxPhotoBytes + 1); // 1 byte over limit
        req.setFileName("large-photo.jpg");

        assertThatThrownBy(() -> mediaService.createUploadUrl(req))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("size");
    }

    @Test
    void videoExceedingMaxSizeThrowsIllegalArgumentException() {
        UploadUrlRequest req = new UploadUrlRequest();
        req.setUploaderId(testUser.getId());
        req.setType(MediaType.VIDEO);
        req.setSizeBytes(maxVideoBytes + 1); // 1 byte over limit
        req.setFileName("large-video.mp4");

        assertThatThrownBy(() -> mediaService.createUploadUrl(req))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("size");
    }

    @Test
    void completeUploadChangesStatusToUploaded() {
        UploadUrlRequest req = new UploadUrlRequest();
        req.setUploaderId(testUser.getId());
        req.setType(MediaType.PHOTO);
        req.setSizeBytes(1_000_000);
        req.setFileName("photo.jpg");

        UploadUrlResponse response = mediaService.createUploadUrl(req);
        String mediaId = response.getMediaId();

        Media pendingMedia = mediaRepository.findById(mediaId).orElseThrow();
        assertThat(pendingMedia.getStatus()).isEqualTo(MediaStatus.PENDING);

        mediaService.completeUpload(mediaId);

        Media uploadedMedia = mediaRepository.findById(mediaId).orElseThrow();
        assertThat(uploadedMedia.getStatus()).isEqualTo(MediaStatus.UPLOADED);
    }

    @Test
    void completeUploadForNonExistentMediaThrowsNoSuchElementException() {
        assertThatThrownBy(() -> mediaService.completeUpload("nonexistent-media-id"))
            .isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    void getMediaReturnsCorrectMedia() {
        UploadUrlRequest req = new UploadUrlRequest();
        req.setUploaderId(testUser.getId());
        req.setType(MediaType.PHOTO);
        req.setSizeBytes(2_000_000);
        req.setFileName("test.jpg");

        UploadUrlResponse response = mediaService.createUploadUrl(req);

        Media media = mediaService.getMedia(response.getMediaId());

        assertThat(media.getId()).isEqualTo(response.getMediaId());
        assertThat(media.getUploaderId()).isEqualTo(testUser.getId());
        assertThat(media.getType()).isEqualTo(MediaType.PHOTO);
    }

    @Test
    void getMediaForNonExistentIdThrowsNoSuchElementException() {
        assertThatThrownBy(() -> mediaService.getMedia("nonexistent-id"))
            .isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    void resolveCdnUrlsReturnsUrlsInOrder() {
        // Create and complete three media uploads
        String mediaId1 = createAndCompleteMedia(MediaType.PHOTO, 1_000_000);
        String mediaId2 = createAndCompleteMedia(MediaType.VIDEO, 10_000_000);
        String mediaId3 = createAndCompleteMedia(MediaType.PHOTO, 2_000_000);

        List<String> urls = mediaService.resolveCdnUrls(List.of(mediaId1, mediaId2, mediaId3));

        assertThat(urls).hasSize(3);

        Media media1 = mediaRepository.findById(mediaId1).orElseThrow();
        Media media2 = mediaRepository.findById(mediaId2).orElseThrow();
        Media media3 = mediaRepository.findById(mediaId3).orElseThrow();

        assertThat(urls.get(0)).isEqualTo(media1.getCdnUrl());
        assertThat(urls.get(1)).isEqualTo(media2.getCdnUrl());
        assertThat(urls.get(2)).isEqualTo(media3.getCdnUrl());
    }

    @Test
    void resolveCdnUrlsWithNonUploadedMediaThrowsIllegalStateException() {
        UploadUrlRequest req = new UploadUrlRequest();
        req.setUploaderId(testUser.getId());
        req.setType(MediaType.PHOTO);
        req.setSizeBytes(1_000_000);
        req.setFileName("pending.jpg");

        UploadUrlResponse response = mediaService.createUploadUrl(req);
        String pendingMediaId = response.getMediaId();

        // Media is still PENDING, not UPLOADED
        assertThatThrownBy(() -> mediaService.resolveCdnUrls(List.of(pendingMediaId)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not uploaded");
    }

    @Test
    void resolveCdnUrlsWithNonExistentMediaThrowsNoSuchElementException() {
        assertThatThrownBy(() -> mediaService.resolveCdnUrls(List.of("nonexistent-id")))
            .isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    void resolveCdnUrlsWithMixedStatusThrowsIllegalStateException() {
        String uploadedId = createAndCompleteMedia(MediaType.PHOTO, 1_000_000);

        UploadUrlRequest req = new UploadUrlRequest();
        req.setUploaderId(testUser.getId());
        req.setType(MediaType.PHOTO);
        req.setSizeBytes(1_000_000);
        req.setFileName("pending.jpg");
        UploadUrlResponse pendingResponse = mediaService.createUploadUrl(req);

        assertThatThrownBy(() -> mediaService.resolveCdnUrls(List.of(uploadedId, pendingResponse.getMediaId())))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not uploaded");
    }

    @Test
    void resolveCdnUrlsWithEmptyListReturnsEmptyList() {
        List<String> urls = mediaService.resolveCdnUrls(List.of());
        assertThat(urls).isEmpty();
    }

    @Test
    void photoAtExactMaxSizeIsAllowed() {
        UploadUrlRequest req = new UploadUrlRequest();
        req.setUploaderId(testUser.getId());
        req.setType(MediaType.PHOTO);
        req.setSizeBytes(maxPhotoBytes); // Exactly at limit
        req.setFileName("max-photo.jpg");

        UploadUrlResponse response = mediaService.createUploadUrl(req);
        assertThat(response.getMediaId()).isNotNull();
    }

    @Test
    void videoAtExactMaxSizeIsAllowed() {
        UploadUrlRequest req = new UploadUrlRequest();
        req.setUploaderId(testUser.getId());
        req.setType(MediaType.VIDEO);
        req.setSizeBytes(maxVideoBytes); // Exactly at limit
        req.setFileName("max-video.mp4");

        UploadUrlResponse response = mediaService.createUploadUrl(req);
        assertThat(response.getMediaId()).isNotNull();
    }

    private String createAndCompleteMedia(MediaType type, long sizeBytes) {
        UploadUrlRequest req = new UploadUrlRequest();
        req.setUploaderId(testUser.getId());
        req.setType(type);
        req.setSizeBytes(sizeBytes);
        req.setFileName("test-file");

        UploadUrlResponse response = mediaService.createUploadUrl(req);
        mediaService.completeUpload(response.getMediaId());
        return response.getMediaId();
    }
}
