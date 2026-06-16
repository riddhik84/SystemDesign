package com.systemdesign.dropbox.service;

import com.systemdesign.dropbox.exception.AccessDeniedException;
import com.systemdesign.dropbox.exception.FileNotFoundException;
import com.systemdesign.dropbox.model.FileMetadata;
import com.systemdesign.dropbox.model.FileStatus;
import com.systemdesign.dropbox.model.ShareRequest;
import com.systemdesign.dropbox.model.SharedFile;
import com.systemdesign.dropbox.repository.FileMetadataRepository;
import com.systemdesign.dropbox.repository.SharedFileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("FileSharingService")
class FileSharingServiceTest {

    @Mock private FileMetadataRepository fileMetadataRepository;
    @Mock private SharedFileRepository sharedFileRepository;
    @Mock private FileSyncService fileSyncService;

    private FileSharingService fileSharingService;

    @BeforeEach
    void setUp() {
        fileSharingService = new FileSharingService(
                fileMetadataRepository, sharedFileRepository, fileSyncService);
    }

    // -------------------------------------------------------------------------
    // shareFile
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("shareFile — owner can share a READY file")
    void shareFile_success() {
        FileMetadata meta = readyFile("file-1", "alice");

        when(fileMetadataRepository.findByFileId("file-1")).thenReturn(Optional.of(meta));
        when(sharedFileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ShareRequest req = new ShareRequest("bob", "READ");
        fileSharingService.shareFile("alice", "file-1", req);

        verify(sharedFileRepository).deleteByFileIdAndSharedWithUserId("file-1", "bob");
        verify(sharedFileRepository).save(any(SharedFile.class));
        verify(fileSyncService).publishChange(eq("bob"), any());
    }

    @Test
    @DisplayName("shareFile — non-owner throws AccessDeniedException")
    void shareFile_nonOwner_throwsAccessDenied() {
        FileMetadata meta = readyFile("file-1", "alice");
        when(fileMetadataRepository.findByFileId("file-1")).thenReturn(Optional.of(meta));

        assertThatThrownBy(() ->
                fileSharingService.shareFile("bob", "file-1", new ShareRequest("carol", "READ")))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("shareFile — file not found throws FileNotFoundException")
    void shareFile_notFound() {
        when(fileMetadataRepository.findByFileId("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                fileSharingService.shareFile("alice", "missing", new ShareRequest("bob", "READ")))
                .isInstanceOf(FileNotFoundException.class);
    }

    @Test
    @DisplayName("shareFile — cannot share UPLOADING file")
    void shareFile_notReady_throwsIllegalArgument() {
        FileMetadata meta = FileMetadata.builder()
                .fileId("file-uploading").ownerId("alice")
                .status(FileStatus.UPLOADING)
                .fileName("test.bin").fileSizeBytes(100L).totalChunks(1)
                .s3Key("files/alice/file-uploading").build();

        when(fileMetadataRepository.findByFileId("file-uploading")).thenReturn(Optional.of(meta));

        assertThatThrownBy(() ->
                fileSharingService.shareFile("alice", "file-uploading", new ShareRequest("bob", "READ")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -------------------------------------------------------------------------
    // unshareFile
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("unshareFile — owner can revoke a share")
    void unshareFile_success() {
        FileMetadata meta = readyFile("file-2", "alice");
        when(fileMetadataRepository.findByFileId("file-2")).thenReturn(Optional.of(meta));

        fileSharingService.unshareFile("alice", "file-2", "bob");

        verify(sharedFileRepository).deleteByFileIdAndSharedWithUserId("file-2", "bob");
    }

    @Test
    @DisplayName("unshareFile — non-owner throws AccessDeniedException")
    void unshareFile_nonOwner_throwsAccessDenied() {
        FileMetadata meta = readyFile("file-3", "alice");
        when(fileMetadataRepository.findByFileId("file-3")).thenReturn(Optional.of(meta));

        assertThatThrownBy(() -> fileSharingService.unshareFile("bob", "file-3", "carol"))
                .isInstanceOf(AccessDeniedException.class);
    }

    // -------------------------------------------------------------------------
    // hasAccess
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("hasAccess — owner always has access")
    void hasAccess_owner_returnsTrue() {
        assertThat(fileSharingService.hasAccess("alice", "file-x", "alice")).isTrue();
    }

    @Test
    @DisplayName("hasAccess — shared user has access")
    void hasAccess_sharedUser_returnsTrue() {
        when(sharedFileRepository.existsByFileIdAndSharedWithUserId("file-x", "bob"))
                .thenReturn(true);

        assertThat(fileSharingService.hasAccess("bob", "file-x", "alice")).isTrue();
    }

    @Test
    @DisplayName("hasAccess — unrelated user has no access")
    void hasAccess_unrelatedUser_returnsFalse() {
        when(sharedFileRepository.existsByFileIdAndSharedWithUserId("file-x", "eve"))
                .thenReturn(false);

        assertThat(fileSharingService.hasAccess("eve", "file-x", "alice")).isFalse();
    }

    // -------------------------------------------------------------------------
    // getSharedFileIds
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getSharedFileIds — returns all fileIds shared with user")
    void getSharedFileIds_returnsCorrectIds() {
        SharedFile sf1 = SharedFile.builder().fileId("f-1").sharedWithUserId("bob")
                .sharedByUserId("alice").permission("READ").build();
        SharedFile sf2 = SharedFile.builder().fileId("f-2").sharedWithUserId("bob")
                .sharedByUserId("carol").permission("READ").build();

        when(sharedFileRepository.findBySharedWithUserId("bob")).thenReturn(List.of(sf1, sf2));

        List<String> ids = fileSharingService.getSharedFileIds("bob");

        assertThat(ids).containsExactlyInAnyOrder("f-1", "f-2");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private FileMetadata readyFile(String fileId, String ownerId) {
        return FileMetadata.builder()
                .fileId(fileId).ownerId(ownerId).status(FileStatus.READY)
                .fileName("file.bin").fileSizeBytes(1024L).totalChunks(1)
                .s3Key("files/" + ownerId + "/" + fileId).build();
    }
}
