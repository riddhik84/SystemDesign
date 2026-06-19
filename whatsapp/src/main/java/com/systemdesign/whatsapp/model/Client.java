package com.systemdesign.whatsapp.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "clients", indexes = {
    @Index(name = "idx_user_id", columnList = "userId"),
    @Index(name = "idx_session_id", columnList = "sessionId")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Client {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String userId;

    @Column(nullable = false, unique = true)
    private String sessionId;

    @Column(nullable = false)
    private String deviceType;

    @Column(nullable = false)
    private String deviceId;

    @Column(nullable = false)
    private Boolean online;

    @Column(nullable = false)
    private LocalDateTime connectedAt;

    private LocalDateTime lastSeenAt;

    @Column(nullable = false)
    private Long lastSequenceNumber;

    @PrePersist
    protected void onCreate() {
        if (connectedAt == null) {
            connectedAt = LocalDateTime.now();
        }
        if (online == null) {
            online = false;
        }
        if (lastSequenceNumber == null) {
            lastSequenceNumber = 0L;
        }
    }
}
