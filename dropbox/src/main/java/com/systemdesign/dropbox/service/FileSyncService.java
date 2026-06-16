package com.systemdesign.dropbox.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.systemdesign.dropbox.model.FileChangeEvent;
import com.systemdesign.dropbox.model.FileMetadata;
import com.systemdesign.dropbox.model.FileStatus;
import com.systemdesign.dropbox.repository.FileMetadataRepository;
import com.systemdesign.dropbox.repository.SharedFileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles the sync protocol: change queries for polling clients and real-time
 * event publishing for WebSocket-connected clients.
 *
 * Sync protocol:
 *
 *   Real-time path:
 *     When a file becomes READY or is shared, publishChange() serializes a
 *     FileChangeEvent to JSON and publishes it to a Redis channel named
 *     "file-changes:{userId}". SyncWebSocketHandler is subscribed to that
 *     channel for each connected user and pushes the JSON to their open
 *     WebSocket session.
 *
 *   Polling / reconnect path:
 *     GET /files/changes?since={timestamp} calls getChanges(). This queries:
 *       a) files owned by the user updated after `since`
 *       b) files shared with the user updated after `since`
 *     The union covers all files the user should see.
 *
 *   Conflict resolution:
 *     Last-write-wins: updatedAt timestamp determines the winner. Clients
 *     apply the most recent server state without merging. This matches Dropbox's
 *     behavior for binary files where three-way merge isn't feasible.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileSyncService {

    /** Redis channel prefix. Full channel: "file-changes:{userId}" */
    public static final String CHANNEL_PREFIX = "file-changes:";

    private final FileMetadataRepository fileMetadataRepository;
    private final SharedFileRepository sharedFileRepository;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Returns all file change events for a user since the given timestamp.
     *
     * Covers:
     *   - Files owned by the user
     *   - Files shared with the user
     *
     * @param userId requesting user
     * @param since  lower-bound timestamp (exclusive) — client's last sync time
     * @return list of FileChangeEvent, one per changed file
     */
    @Transactional(readOnly = true)
    public List<FileChangeEvent> getChanges(String userId, Instant since) {
        LocalDateTime sinceLocal = LocalDateTime.ofInstant(since, ZoneOffset.UTC);

        // 1. Files the user owns that changed since `since`.
        List<FileMetadata> ownedChanges = fileMetadataRepository
                .findByOwnerIdAndStatusAndUpdatedAtAfter(userId, FileStatus.READY, sinceLocal);

        // 2. Files shared with the user that changed since `since`.
        List<String> sharedFileIds = sharedFileRepository.findBySharedWithUserId(userId)
                .stream()
                .map(sf -> sf.getFileId())
                .toList();

        List<FileMetadata> sharedChanges = sharedFileIds.isEmpty()
                ? List.of()
                : fileMetadataRepository.findByFileIdInAndStatusAndUpdatedAtAfter(
                        sharedFileIds, FileStatus.READY, sinceLocal);

        // Build events from the combined set.
        List<FileChangeEvent> events = new ArrayList<>();
        for (FileMetadata fm : ownedChanges) {
            events.add(toChangeEvent(fm, "UPDATED"));
        }
        for (FileMetadata fm : sharedChanges) {
            events.add(toChangeEvent(fm, "UPDATED"));
        }

        log.debug("Changes since {}: userId={}, count={}", since, userId, events.size());
        return events;
    }

    /**
     * Publishes a FileChangeEvent to the user's Redis pub/sub channel.
     *
     * SyncWebSocketHandler subscribes to "file-changes:{userId}" for each
     * connected session and forwards the JSON payload over the WebSocket.
     *
     * If Redis is unavailable, the exception is logged but not propagated —
     * missing a real-time push is acceptable because clients can fall back
     * to polling GET /files/changes.
     *
     * @param userId recipient user
     * @param event  event to publish
     */
    public void publishChange(String userId, FileChangeEvent event) {
        String channel = CHANNEL_PREFIX + userId;
        try {
            String json = objectMapper.writeValueAsString(event);
            stringRedisTemplate.convertAndSend(channel, json);
            log.debug("Published change event: channel={}, eventType={}", channel, event.getEventType());
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize FileChangeEvent for channel {}", channel, e);
        } catch (Exception e) {
            // Redis pub/sub failure must not fail the primary write transaction.
            log.error("Failed to publish to Redis channel {}", channel, e);
        }
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private FileChangeEvent toChangeEvent(FileMetadata fm, String eventType) {
        return FileChangeEvent.builder()
                .fileId(fm.getFileId())
                .fileName(fm.getFileName())
                .ownerId(fm.getOwnerId())
                .eventType(eventType)
                .fileStatus(fm.getStatus())
                .occurredAt(fm.getUpdatedAt().toInstant(ZoneOffset.UTC))
                .build();
    }
}
