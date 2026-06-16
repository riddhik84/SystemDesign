package com.systemdesign.dropbox.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Event published to Redis and pushed over WebSocket to connected clients.
 *
 * Also returned in the GET /files/changes?since={ts} polling response so clients
 * that miss WebSocket events can catch up on reconnect.
 *
 * eventType values:
 *   CREATED  — new file is READY
 *   UPDATED  — file metadata changed (name rename, etc.)
 *   DELETED  — file moved to DELETED state
 *   SHARED   — a file was shared with this user
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileChangeEvent {

    private String fileId;
    private String fileName;
    private String ownerId;
    private String eventType;
    private FileStatus fileStatus;

    /** Wall-clock time this event was generated (server time). */
    private Instant occurredAt;
}
