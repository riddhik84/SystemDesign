package com.systemdesign.dropbox.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.systemdesign.dropbox.model.FileChangeEvent;
import com.systemdesign.dropbox.model.FileMetadata;
import com.systemdesign.dropbox.model.FileStatus;
import com.systemdesign.dropbox.model.SharedFile;
import com.systemdesign.dropbox.repository.FileMetadataRepository;
import com.systemdesign.dropbox.repository.SharedFileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("FileSyncService")
class FileSyncServiceTest {

    @Mock private FileMetadataRepository fileMetadataRepository;
    @Mock private SharedFileRepository sharedFileRepository;
    @Mock private StringRedisTemplate stringRedisTemplate;

    private ObjectMapper objectMapper;
    private FileSyncService fileSyncService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        fileSyncService = new FileSyncService(
                fileMetadataRepository, sharedFileRepository, stringRedisTemplate, objectMapper);
    }

    // -------------------------------------------------------------------------
    // getChanges
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getChanges — returns owned file changes since timestamp")
    void getChanges_ownedFilesReturned() {
        Instant since = Instant.parse("2024-01-01T00:00:00Z");
        LocalDateTime sinceLocal = LocalDateTime.ofInstant(since, ZoneOffset.UTC);

        FileMetadata fm = readyFile("f-1", "alice", "photo.jpg");

        when(fileMetadataRepository.findByOwnerIdAndStatusAndUpdatedAtAfter(
                eq("alice"), eq(FileStatus.READY), any(LocalDateTime.class)))
                .thenReturn(List.of(fm));
        when(sharedFileRepository.findBySharedWithUserId("alice")).thenReturn(List.of());

        List<FileChangeEvent> events = fileSyncService.getChanges("alice", since);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).getFileId()).isEqualTo("f-1");
        assertThat(events.get(0).getFileName()).isEqualTo("photo.jpg");
        assertThat(events.get(0).getEventType()).isEqualTo("UPDATED");
    }

    @Test
    @DisplayName("getChanges — includes files shared with user")
    void getChanges_sharedFilesIncluded() {
        Instant since = Instant.parse("2024-06-01T00:00:00Z");

        SharedFile share = SharedFile.builder()
                .fileId("shared-file-1").sharedWithUserId("bob")
                .sharedByUserId("alice").permission("READ").build();

        FileMetadata sharedMeta = readyFile("shared-file-1", "alice", "report.pdf");

        when(fileMetadataRepository.findByOwnerIdAndStatusAndUpdatedAtAfter(
                eq("bob"), eq(FileStatus.READY), any())).thenReturn(List.of());
        when(sharedFileRepository.findBySharedWithUserId("bob")).thenReturn(List.of(share));
        when(fileMetadataRepository.findByFileIdInAndStatusAndUpdatedAtAfter(
                eq(List.of("shared-file-1")), eq(FileStatus.READY), any()))
                .thenReturn(List.of(sharedMeta));

        List<FileChangeEvent> events = fileSyncService.getChanges("bob", since);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).getFileId()).isEqualTo("shared-file-1");
    }

    @Test
    @DisplayName("getChanges — returns empty list when no changes")
    void getChanges_noChanges_returnsEmpty() {
        when(fileMetadataRepository.findByOwnerIdAndStatusAndUpdatedAtAfter(any(), any(), any()))
                .thenReturn(List.of());
        when(sharedFileRepository.findBySharedWithUserId(any())).thenReturn(List.of());

        List<FileChangeEvent> events = fileSyncService.getChanges("alice", Instant.now());

        assertThat(events).isEmpty();
    }

    // -------------------------------------------------------------------------
    // publishChange
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("publishChange — serializes event and publishes to correct Redis channel")
    void publishChange_publishesToCorrectChannel() {
        FileChangeEvent event = FileChangeEvent.builder()
                .fileId("f-1").fileName("doc.pdf").ownerId("alice")
                .eventType("CREATED").fileStatus(FileStatus.READY)
                .occurredAt(Instant.now()).build();

        fileSyncService.publishChange("alice", event);

        ArgumentCaptor<String> channelCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(stringRedisTemplate).convertAndSend(channelCaptor.capture(), messageCaptor.capture());

        assertThat(channelCaptor.getValue()).isEqualTo("file-changes:alice");
        assertThat(messageCaptor.getValue()).contains("f-1").contains("CREATED");
    }

    @Test
    @DisplayName("publishChange — Redis failure does not propagate exception")
    void publishChange_redisFailure_doesNotThrow() {
        doThrow(new RuntimeException("Redis down"))
                .when(stringRedisTemplate).convertAndSend(anyString(), anyString());

        FileChangeEvent event = FileChangeEvent.builder()
                .fileId("f-2").fileName("x.bin").ownerId("bob")
                .eventType("CREATED").fileStatus(FileStatus.READY)
                .occurredAt(Instant.now()).build();

        // Must not throw — Redis failure is non-fatal, clients fall back to polling.
        assertThatNoException().isThrownBy(() -> fileSyncService.publishChange("bob", event));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private FileMetadata readyFile(String fileId, String ownerId, String fileName) {
        return FileMetadata.builder()
                .fileId(fileId).ownerId(ownerId).status(FileStatus.READY)
                .fileName(fileName).fileSizeBytes(1024L).totalChunks(1)
                .s3Key("files/" + ownerId + "/" + fileId)
                .updatedAt(LocalDateTime.now()).build();
    }
}
