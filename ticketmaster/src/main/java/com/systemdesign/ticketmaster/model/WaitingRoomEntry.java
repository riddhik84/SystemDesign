package com.systemdesign.ticketmaster.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "waiting_room_entries", indexes = {
    @Index(name = "idx_event_position", columnList = "event_id,queue_position"),
    @Index(name = "idx_session", columnList = "session_id", unique = true)
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WaitingRoomEntry {

    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @Column(nullable = false, length = 100)
    private String userId;

    @Column(nullable = false, unique = true, length = 200)
    private String sessionId;

    @Column(nullable = false)
    private Integer queuePosition;

    @Column(nullable = false)
    private Instant joinedAt;

    private Instant allowedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private QueueStatus status = QueueStatus.WAITING;

    public enum QueueStatus {
        WAITING,
        ALLOWED,
        EXPIRED
    }
}
