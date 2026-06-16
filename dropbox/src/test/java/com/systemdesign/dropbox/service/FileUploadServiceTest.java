package com.systemdesign.dropbox.service;

import com.amazonaws.services.s3.model.PartETag;
import com.systemdesign.dropbox.config.AppProperties;
import com.systemdesign.dropbox.exception.AccessDeniedException;
import com.systemdesign.dropbox.exception.FileNotFoundException;
import com.systemdesign.dropbox.model.ChunkEtag;
import com.systemdesign.dropbox.model.CompleteUploadRequest;
import com.systemdesign.dropbox.model.FileChunk;
import com.systemdesign.dropbox.model.FileMetadata;
import com.systemdesign.dropbox.model.FileStatus;
import com.systemdesign.dropbox.model.InitiateUploadRequest;
import com.systemdesign.dropbox.model.InitiateUploadResponse;
import com.systemdesign.dropbox.repository.FileChunkRepository;
import com.systemdesign.dropbox.repository.FileMetadataRepository;
import com.systemdesign.dropbox.storage.S3StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("FileUploadService")
class FileUploadServiceTest {

    @Mock private FileMetadataRepository fileMetadataRepository;
    @Mock private FileChunkRepository fileChunkRepository;
    @Mock private S3StorageService s3StorageService;
    @Mock private FileSyncService fileSyncService;

    private AppProperties appProperties;

    @InjectMocks
    private FileUploadService fileUploadService;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        appProperties.setChunkSizeBytes(8_388_608L);   // 8 MB
        AppProperties.S3Properties s3Props = new AppProperties.S3Properties();
        s3Props.setPresignedUrlExpiryMinutes(5);
        s3Props.setBucketName("test-bucket");
        s3Props.setRegion("us-east-1");
        appProperties.setS3(s3Props);

        // Re-inject appProperties since @InjectMocks used constructor injection
        fileUploadService = new FileUploadService(
                fileMetadataRepository,
                fileChunkRepository,
                s3StorageService,
                fileSyncService,
                appProperties);
    }

    // -------------------------------------------------------------------------
    // initiateUpload
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("initiateUpload — single chunk for small file")
    void initiateUpload_smallFile_singleChunk() {
        InitiateUploadRequest req = InitiateUploadRequest.builder()
                .fileName("photo.jpg")
                .fileSizeBytes(1_024L)    // 1 KB — fits in one chunk
                .mimeType("image/jpeg")
                .build();

        when(s3StorageService.initiateMultipartUpload(anyString())).thenReturn("upload-id-1");
        when(fileMetadataRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(fileChunkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(s3StorageService.generatePresignedUploadUrl(anyString(), anyString(), anyInt(), any(Duration.class)))
                .thenReturn("https://s3.example.com/presigned-1");

        InitiateUploadResponse response = fileUploadService.initiateUpload("user-alice", req);

        assertThat(response.getFileId()).isNotBlank();
        assertThat(response.getTotalChunks()).isEqualTo(1);
        assertThat(response.getChunkUrls()).hasSize(1);
        assertThat(response.getChunkUrls().get(0).getChunkNumber()).isEqualTo(1);
        assertThat(response.getChunkUrls().get(0).getPresignedUrl())
                .isEqualTo("https://s3.example.com/presigned-1");

        verify(s3StorageService).initiateMultipartUpload(anyString());
        verify(fileMetadataRepository).save(any(FileMetadata.class));
        verify(fileChunkRepository, times(1)).save(any(FileChunk.class));
    }

    @Test
    @DisplayName("initiateUpload — multi-chunk for large file")
    void initiateUpload_largeFile_multipleChunks() {
        long fileSize = 20_000_000L;   // ~19 MB → 3 chunks with 8 MB chunks
        int expectedChunks = (int) Math.ceil((double) fileSize / 8_388_608L);  // 3

        InitiateUploadRequest req = InitiateUploadRequest.builder()
                .fileName("video.mp4")
                .fileSizeBytes(fileSize)
                .mimeType("video/mp4")
                .build();

        when(s3StorageService.initiateMultipartUpload(anyString())).thenReturn("upload-id-multi");
        when(fileMetadataRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(fileChunkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(s3StorageService.generatePresignedUploadUrl(anyString(), anyString(), anyInt(), any(Duration.class)))
                .thenReturn("https://s3.example.com/presigned");

        InitiateUploadResponse response = fileUploadService.initiateUpload("user-bob", req);

        assertThat(response.getTotalChunks()).isEqualTo(expectedChunks);
        assertThat(response.getChunkUrls()).hasSize(expectedChunks);
        verify(fileChunkRepository, times(expectedChunks)).save(any(FileChunk.class));
    }

    @Test
    @DisplayName("initiateUpload — s3Key follows pattern files/{ownerId}/{fileId}")
    void initiateUpload_s3KeyPattern() {
        InitiateUploadRequest req = InitiateUploadRequest.builder()
                .fileName("doc.pdf")
                .fileSizeBytes(500L)
                .build();

        when(s3StorageService.initiateMultipartUpload(anyString())).thenReturn("uid");
        when(fileMetadataRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(fileChunkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(s3StorageService.generatePresignedUploadUrl(anyString(), anyString(), anyInt(), any()))
                .thenReturn("https://url");

        fileUploadService.initiateUpload("owner-x", req);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(s3StorageService).initiateMultipartUpload(keyCaptor.capture());
        assertThat(keyCaptor.getValue()).startsWith("files/owner-x/");
    }

    @Test
    @DisplayName("initiateUpload — FileMetadata saved with UPLOADING status")
    void initiateUpload_metadataStatusIsUploading() {
        InitiateUploadRequest req = InitiateUploadRequest.builder()
                .fileName("data.csv")
                .fileSizeBytes(100L)
                .build();

        when(s3StorageService.initiateMultipartUpload(anyString())).thenReturn("uid");
        when(fileMetadataRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(fileChunkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(s3StorageService.generatePresignedUploadUrl(anyString(), anyString(), anyInt(), any()))
                .thenReturn("https://url");

        fileUploadService.initiateUpload("user-z", req);

        ArgumentCaptor<FileMetadata> metaCaptor = ArgumentCaptor.forClass(FileMetadata.class);
        verify(fileMetadataRepository).save(metaCaptor.capture());
        assertThat(metaCaptor.getValue().getStatus()).isEqualTo(FileStatus.UPLOADING);
    }

    // -------------------------------------------------------------------------
    // completeUpload
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("completeUpload — success: marks file READY and publishes change")
    void completeUpload_success() {
        String fileId = "file-123";
        String ownerId = "alice";

        FileMetadata metadata = FileMetadata.builder()
                .fileId(fileId)
                .ownerId(ownerId)
                .status(FileStatus.UPLOADING)
                .s3Key("files/alice/file-123")
                .s3UploadId("s3-upload-id")
                .totalChunks(2)
                .fileName("archive.zip")
                .fileSizeBytes(16_000_000L)
                .build();

        FileChunk chunk1 = FileChunk.builder().fileId(fileId).chunkNumber(1).uploaded(false).build();
        FileChunk chunk2 = FileChunk.builder().fileId(fileId).chunkNumber(2).uploaded(false).build();

        when(fileMetadataRepository.findByFileId(fileId)).thenReturn(Optional.of(metadata));
        when(fileChunkRepository.findByFileIdOrderByChunkNumber(fileId)).thenReturn(List.of(chunk1, chunk2));
        when(fileChunkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(fileMetadataRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CompleteUploadRequest req = CompleteUploadRequest.builder()
                .chunkEtags(List.of(
                        new ChunkEtag(1, "etag-1"),
                        new ChunkEtag(2, "etag-2")))
                .checksum("sha256-abc")
                .build();

        FileMetadata result = fileUploadService.completeUpload(ownerId, fileId, req);

        assertThat(result.getStatus()).isEqualTo(FileStatus.READY);
        assertThat(result.getS3UploadId()).isNull();
        assertThat(result.getChecksum()).isEqualTo("sha256-abc");

        verify(s3StorageService).completeMultipartUpload(
                eq("files/alice/file-123"), eq("s3-upload-id"), any());
        verify(fileSyncService).publishChange(eq(ownerId), any());
    }

    @Test
    @DisplayName("completeUpload — wrong owner throws AccessDeniedException")
    void completeUpload_wrongOwner_throwsAccessDenied() {
        FileMetadata metadata = FileMetadata.builder()
                .fileId("file-x")
                .ownerId("alice")
                .status(FileStatus.UPLOADING)
                .totalChunks(1)
                .build();

        when(fileMetadataRepository.findByFileId("file-x")).thenReturn(Optional.of(metadata));

        CompleteUploadRequest req = CompleteUploadRequest.builder()
                .chunkEtags(List.of(new ChunkEtag(1, "etag-1")))
                .build();

        assertThatThrownBy(() -> fileUploadService.completeUpload("bob", "file-x", req))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("completeUpload — file not found throws FileNotFoundException")
    void completeUpload_fileNotFound() {
        when(fileMetadataRepository.findByFileId("missing")).thenReturn(Optional.empty());

        CompleteUploadRequest req = CompleteUploadRequest.builder()
                .chunkEtags(List.of(new ChunkEtag(1, "etag-1")))
                .build();

        assertThatThrownBy(() -> fileUploadService.completeUpload("alice", "missing", req))
                .isInstanceOf(FileNotFoundException.class);
    }

    @Test
    @DisplayName("completeUpload — wrong chunk count throws IllegalArgumentException")
    void completeUpload_wrongChunkCount() {
        FileMetadata metadata = FileMetadata.builder()
                .fileId("file-y")
                .ownerId("alice")
                .status(FileStatus.UPLOADING)
                .totalChunks(3)  // expects 3 etags
                .s3Key("files/alice/file-y")
                .s3UploadId("uid")
                .fileName("big.zip")
                .fileSizeBytes(24_000_000L)
                .build();

        when(fileMetadataRepository.findByFileId("file-y")).thenReturn(Optional.of(metadata));

        CompleteUploadRequest req = CompleteUploadRequest.builder()
                .chunkEtags(List.of(new ChunkEtag(1, "etag-1")))  // only 1 etag
                .build();

        assertThatThrownBy(() -> fileUploadService.completeUpload("alice", "file-y", req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Expected 3");
    }

    @Test
    @DisplayName("completeUpload — PartETags are sorted by chunk number before S3 call")
    void completeUpload_partETagsSortedBeforeS3Call() {
        String fileId = "file-sort";
        FileMetadata metadata = FileMetadata.builder()
                .fileId(fileId).ownerId("alice").status(FileStatus.UPLOADING)
                .s3Key("files/alice/file-sort").s3UploadId("uid")
                .totalChunks(2).fileName("f.bin").fileSizeBytes(10_000_000L)
                .build();

        FileChunk c1 = FileChunk.builder().fileId(fileId).chunkNumber(1).uploaded(false).build();
        FileChunk c2 = FileChunk.builder().fileId(fileId).chunkNumber(2).uploaded(false).build();

        when(fileMetadataRepository.findByFileId(fileId)).thenReturn(Optional.of(metadata));
        when(fileChunkRepository.findByFileIdOrderByChunkNumber(fileId)).thenReturn(List.of(c1, c2));
        when(fileChunkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(fileMetadataRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Provide etags out of order — service must sort them.
        CompleteUploadRequest req = CompleteUploadRequest.builder()
                .chunkEtags(List.of(
                        new ChunkEtag(2, "etag-2"),
                        new ChunkEtag(1, "etag-1")))
                .build();

        fileUploadService.completeUpload("alice", fileId, req);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PartETag>> etagsCaptor = ArgumentCaptor.forClass(List.class);
        verify(s3StorageService).completeMultipartUpload(anyString(), anyString(), etagsCaptor.capture());

        List<PartETag> captured = etagsCaptor.getValue();
        assertThat(captured.get(0).getPartNumber()).isEqualTo(1);
        assertThat(captured.get(1).getPartNumber()).isEqualTo(2);
    }
}
