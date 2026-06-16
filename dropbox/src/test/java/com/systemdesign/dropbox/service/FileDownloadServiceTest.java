package com.systemdesign.dropbox.service;

import com.systemdesign.dropbox.config.AppProperties;
import com.systemdesign.dropbox.exception.AccessDeniedException;
import com.systemdesign.dropbox.exception.FileNotFoundException;
import com.systemdesign.dropbox.exception.UploadIncompleteException;
import com.systemdesign.dropbox.model.FileMetadata;
import com.systemdesign.dropbox.model.FileStatus;
import com.systemdesign.dropbox.repository.FileMetadataRepository;
import com.systemdesign.dropbox.storage.S3StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("FileDownloadService")
class FileDownloadServiceTest {

    @Mock private FileMetadataRepository fileMetadataRepository;
    @Mock private FileSharingService fileSharingService;
    @Mock private S3StorageService s3StorageService;

    private AppProperties appProperties;
    private FileDownloadService fileDownloadService;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        AppProperties.S3Properties s3Props = new AppProperties.S3Properties();
        s3Props.setPresignedUrlExpiryMinutes(5);
        appProperties.setS3(s3Props);

        fileDownloadService = new FileDownloadService(
                fileMetadataRepository, fileSharingService, s3StorageService, appProperties);
    }

    @Test
    @DisplayName("getDownloadUrl — owner can download their own READY file")
    void getDownloadUrl_ownerAccess() {
        FileMetadata meta = readyFile("file-1", "owner-alice");

        when(fileMetadataRepository.findByFileId("file-1")).thenReturn(Optional.of(meta));
        when(fileSharingService.hasAccess("owner-alice", "file-1", "owner-alice")).thenReturn(true);
        when(s3StorageService.generatePresignedDownloadUrl(anyString(), any(Duration.class)))
                .thenReturn("https://s3.example.com/download");

        String url = fileDownloadService.getDownloadUrl("owner-alice", "file-1");

        assertThat(url).isEqualTo("https://s3.example.com/download");
        verify(s3StorageService).generatePresignedDownloadUrl(eq("files/owner-alice/file-1"), any());
    }

    @Test
    @DisplayName("getDownloadUrl — shared user can download")
    void getDownloadUrl_sharedAccess() {
        FileMetadata meta = readyFile("file-2", "owner-alice");

        when(fileMetadataRepository.findByFileId("file-2")).thenReturn(Optional.of(meta));
        when(fileSharingService.hasAccess("bob", "file-2", "owner-alice")).thenReturn(true);
        when(s3StorageService.generatePresignedDownloadUrl(anyString(), any()))
                .thenReturn("https://s3.example.com/shared-download");

        String url = fileDownloadService.getDownloadUrl("bob", "file-2");

        assertThat(url).isEqualTo("https://s3.example.com/shared-download");
    }

    @Test
    @DisplayName("getDownloadUrl — unrelated user throws AccessDeniedException")
    void getDownloadUrl_noAccess_throwsAccessDenied() {
        FileMetadata meta = readyFile("file-3", "owner-alice");

        when(fileMetadataRepository.findByFileId("file-3")).thenReturn(Optional.of(meta));
        when(fileSharingService.hasAccess("eve", "file-3", "owner-alice")).thenReturn(false);

        assertThatThrownBy(() -> fileDownloadService.getDownloadUrl("eve", "file-3"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("getDownloadUrl — file not found throws FileNotFoundException")
    void getDownloadUrl_notFound() {
        when(fileMetadataRepository.findByFileId("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> fileDownloadService.getDownloadUrl("alice", "missing"))
                .isInstanceOf(FileNotFoundException.class);
    }

    @Test
    @DisplayName("getDownloadUrl — UPLOADING file throws UploadIncompleteException")
    void getDownloadUrl_uploading_throwsIncomplete() {
        FileMetadata meta = FileMetadata.builder()
                .fileId("file-4").ownerId("alice")
                .status(FileStatus.UPLOADING)
                .s3Key("files/alice/file-4")
                .fileName("test.bin").fileSizeBytes(100L).totalChunks(1).build();

        when(fileMetadataRepository.findByFileId("file-4")).thenReturn(Optional.of(meta));

        assertThatThrownBy(() -> fileDownloadService.getDownloadUrl("alice", "file-4"))
                .isInstanceOf(UploadIncompleteException.class);
    }

    @Test
    @DisplayName("getDownloadUrl — DELETED file throws FileNotFoundException")
    void getDownloadUrl_deleted_throwsNotFound() {
        FileMetadata meta = FileMetadata.builder()
                .fileId("file-5").ownerId("alice")
                .status(FileStatus.DELETED)
                .s3Key("files/alice/file-5")
                .fileName("gone.bin").fileSizeBytes(100L).totalChunks(1).build();

        when(fileMetadataRepository.findByFileId("file-5")).thenReturn(Optional.of(meta));

        assertThatThrownBy(() -> fileDownloadService.getDownloadUrl("alice", "file-5"))
                .isInstanceOf(FileNotFoundException.class);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private FileMetadata readyFile(String fileId, String ownerId) {
        return FileMetadata.builder()
                .fileId(fileId)
                .ownerId(ownerId)
                .status(FileStatus.READY)
                .s3Key("files/" + ownerId + "/" + fileId)
                .fileName("test.bin")
                .fileSizeBytes(1024L)
                .totalChunks(1)
                .build();
    }
}
