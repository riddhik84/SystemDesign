package com.systemdesign.dropbox.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.systemdesign.dropbox.exception.AccessDeniedException;
import com.systemdesign.dropbox.exception.FileNotFoundException;
import com.systemdesign.dropbox.exception.GlobalExceptionHandler;
import com.systemdesign.dropbox.exception.UploadIncompleteException;
import com.systemdesign.dropbox.model.ChunkEtag;
import com.systemdesign.dropbox.model.CompleteUploadRequest;
import com.systemdesign.dropbox.model.FileChangeEvent;
import com.systemdesign.dropbox.model.FileMetadata;
import com.systemdesign.dropbox.model.FileStatus;
import com.systemdesign.dropbox.model.InitiateUploadRequest;
import com.systemdesign.dropbox.model.InitiateUploadResponse;
import com.systemdesign.dropbox.model.PresignedChunkUrl;
import com.systemdesign.dropbox.model.ShareRequest;
import com.systemdesign.dropbox.service.FileDownloadService;
import com.systemdesign.dropbox.service.FileSharingService;
import com.systemdesign.dropbox.service.FileSyncService;
import com.systemdesign.dropbox.service.FileUploadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone MockMvc tests for FileController.
 * Uses MockitoExtension — no Spring context, no real DB or Redis.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FileController")
class FileControllerTest {

    @Mock private FileUploadService fileUploadService;
    @Mock private FileDownloadService fileDownloadService;
    @Mock private FileSharingService fileSharingService;
    @Mock private FileSyncService fileSyncService;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        FileController controller = new FileController(
                fileUploadService, fileDownloadService, fileSharingService, fileSyncService);

        MappingJackson2HttpMessageConverter converter =
                new MappingJackson2HttpMessageConverter(objectMapper);

        mockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(converter)
                .build();
    }

    // -------------------------------------------------------------------------
    // POST /files/initiate
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("POST /files/initiate — 201 with presigned URLs")
    void initiateUpload_returns201() throws Exception {
        InitiateUploadResponse response = InitiateUploadResponse.builder()
                .fileId("file-uuid-1")
                .totalChunks(2)
                .chunkUrls(List.of(
                        new PresignedChunkUrl(1, "https://s3.example.com/part1"),
                        new PresignedChunkUrl(2, "https://s3.example.com/part2")))
                .build();

        when(fileUploadService.initiateUpload(eq("alice"), any(InitiateUploadRequest.class)))
                .thenReturn(response);

        InitiateUploadRequest req = new InitiateUploadRequest("video.mp4", 20_000_000L,
                "video/mp4", null, false);

        mockMvc.perform(post("/files/initiate")
                        .header("X-User-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fileId").value("file-uuid-1"))
                .andExpect(jsonPath("$.totalChunks").value(2))
                .andExpect(jsonPath("$.chunkUrls").isArray())
                .andExpect(jsonPath("$.chunkUrls[0].chunkNumber").value(1));
    }

    @Test
    @DisplayName("POST /files/initiate — 400 when fileName missing")
    void initiateUpload_missingFileName_returns400() throws Exception {
        InitiateUploadRequest req = new InitiateUploadRequest("", 1000L, null, null, false);

        mockMvc.perform(post("/files/initiate")
                        .header("X-User-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /files/initiate — 400 when fileSizeBytes is zero")
    void initiateUpload_zeroSize_returns400() throws Exception {
        InitiateUploadRequest req = new InitiateUploadRequest("file.bin", 0L, null, null, false);

        mockMvc.perform(post("/files/initiate")
                        .header("X-User-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------
    // POST /files/{fileId}/complete
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("POST /files/{fileId}/complete — 200 with READY metadata")
    void completeUpload_returns200() throws Exception {
        FileMetadata meta = FileMetadata.builder()
                .fileId("file-1").ownerId("alice").status(FileStatus.READY)
                .fileName("archive.zip").fileSizeBytes(16_000_000L).totalChunks(2)
                .s3Key("files/alice/file-1").build();

        when(fileUploadService.completeUpload(eq("alice"), eq("file-1"), any()))
                .thenReturn(meta);

        CompleteUploadRequest req = new CompleteUploadRequest(
                List.of(new ChunkEtag(1, "etag-1"), new ChunkEtag(2, "etag-2")), null);

        mockMvc.perform(post("/files/file-1/complete")
                        .header("X-User-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileId").value("file-1"))
                .andExpect(jsonPath("$.status").value("READY"));
    }

    @Test
    @DisplayName("POST /files/{fileId}/complete — 403 for wrong owner")
    void completeUpload_wrongOwner_returns403() throws Exception {
        when(fileUploadService.completeUpload(eq("bob"), eq("file-1"), any()))
                .thenThrow(new AccessDeniedException("bob", "file-1"));

        CompleteUploadRequest req = new CompleteUploadRequest(
                List.of(new ChunkEtag(1, "etag-1")), null);

        mockMvc.perform(post("/files/file-1/complete")
                        .header("X-User-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /files/{fileId}/complete — 404 when file not found")
    void completeUpload_notFound_returns404() throws Exception {
        when(fileUploadService.completeUpload(anyString(), eq("missing"), any()))
                .thenThrow(new FileNotFoundException("missing"));

        CompleteUploadRequest req = new CompleteUploadRequest(
                List.of(new ChunkEtag(1, "etag-1")), null);

        mockMvc.perform(post("/files/missing/complete")
                        .header("X-User-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // GET /files/{fileId}/presigned-url
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GET /files/{fileId}/presigned-url — 200 with URL")
    void getPresignedDownloadUrl_returns200() throws Exception {
        when(fileDownloadService.getDownloadUrl("alice", "file-1"))
                .thenReturn("https://cdn.example.com/file-1?sig=abc");

        mockMvc.perform(get("/files/file-1/presigned-url")
                        .header("X-User-Id", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://cdn.example.com/file-1?sig=abc"));
    }

    @Test
    @DisplayName("GET /files/{fileId}/presigned-url — 403 for unauthorized user")
    void getPresignedDownloadUrl_noAccess_returns403() throws Exception {
        when(fileDownloadService.getDownloadUrl("eve", "file-1"))
                .thenThrow(new AccessDeniedException("eve", "file-1"));

        mockMvc.perform(get("/files/file-1/presigned-url")
                        .header("X-User-Id", "eve"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /files/{fileId}/presigned-url — 404 when file not found")
    void getPresignedDownloadUrl_notFound_returns404() throws Exception {
        when(fileDownloadService.getDownloadUrl("alice", "ghost"))
                .thenThrow(new FileNotFoundException("ghost"));

        mockMvc.perform(get("/files/ghost/presigned-url")
                        .header("X-User-Id", "alice"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /files/{fileId}/presigned-url — 409 when upload incomplete")
    void getPresignedDownloadUrl_incomplete_returns409() throws Exception {
        when(fileDownloadService.getDownloadUrl("alice", "file-2"))
                .thenThrow(new UploadIncompleteException("file-2"));

        mockMvc.perform(get("/files/file-2/presigned-url")
                        .header("X-User-Id", "alice"))
                .andExpect(status().isConflict());
    }

    // -------------------------------------------------------------------------
    // POST /files/{fileId}/share
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("POST /files/{fileId}/share — 204 on success")
    void shareFile_returns204() throws Exception {
        doNothing().when(fileSharingService).shareFile(anyString(), anyString(), any());

        ShareRequest req = new ShareRequest("bob", "READ");

        mockMvc.perform(post("/files/file-1/share")
                        .header("X-User-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNoContent());

        verify(fileSharingService).shareFile(eq("alice"), eq("file-1"), any(ShareRequest.class));
    }

    @Test
    @DisplayName("POST /files/{fileId}/share — 400 when targetUserId blank")
    void shareFile_blankTarget_returns400() throws Exception {
        ShareRequest req = new ShareRequest("", "READ");

        mockMvc.perform(post("/files/file-1/share")
                        .header("X-User-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /files/{fileId}/share — 403 when non-owner tries to share")
    void shareFile_nonOwner_returns403() throws Exception {
        doThrow(new AccessDeniedException("Only the file owner can share"))
                .when(fileSharingService).shareFile(anyString(), anyString(), any());

        ShareRequest req = new ShareRequest("carol", "READ");

        mockMvc.perform(post("/files/file-1/share")
                        .header("X-User-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------------------------
    // DELETE /files/{fileId}/share/{userId}
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("DELETE /files/{fileId}/share/{userId} — 204 on success")
    void unshareFile_returns204() throws Exception {
        doNothing().when(fileSharingService).unshareFile("alice", "file-1", "bob");

        mockMvc.perform(delete("/files/file-1/share/bob")
                        .header("X-User-Id", "alice"))
                .andExpect(status().isNoContent());

        verify(fileSharingService).unshareFile("alice", "file-1", "bob");
    }

    // -------------------------------------------------------------------------
    // GET /files/changes
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GET /files/changes — 200 with change events")
    void getChanges_returns200() throws Exception {
        FileChangeEvent event = FileChangeEvent.builder()
                .fileId("f-1").fileName("doc.pdf").ownerId("alice")
                .eventType("CREATED").fileStatus(FileStatus.READY)
                .occurredAt(Instant.parse("2024-06-01T10:00:00Z"))
                .build();

        when(fileSyncService.getChanges(eq("alice"), any(Instant.class)))
                .thenReturn(List.of(event));

        mockMvc.perform(get("/files/changes")
                        .header("X-User-Id", "alice")
                        .param("since", "2024-01-01T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].fileId").value("f-1"))
                .andExpect(jsonPath("$[0].eventType").value("CREATED"));
    }

    @Test
    @DisplayName("GET /files/changes — 200 with empty list when no changes")
    void getChanges_noChanges_returnsEmptyList() throws Exception {
        when(fileSyncService.getChanges(anyString(), any())).thenReturn(List.of());

        mockMvc.perform(get("/files/changes")
                        .header("X-User-Id", "alice")
                        .param("since", "2024-01-01T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }
}
