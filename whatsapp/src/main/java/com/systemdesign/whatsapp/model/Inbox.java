package com.systemdesign.whatsapp.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "inbox",
       uniqueConstraints = @UniqueConstraint(columnNames = {"clientId", "messageId"}),
       indexes = {
           @Index(name = "idx_client_timestamp", columnList = "clientId,timestamp"),
           @Index(name = "idx_expires_at", columnList = "expiresAt")
       })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Inbox {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String clientId;

    @Column(nullable = false)
    private String userId;

    @Column(nullable = false)
    private String messageId;

    @Column(nullable = false)
    private String chatId;

    @Column(nullable = false)
    private LocalDateTime timestamp;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @Column(nullable = false)
    private Boolean delivered;

    @PrePersist
    protected void onCreate() {
        if (timestamp == null) {
            timestamp = LocalDateTime.now();
        }
        if (expiresAt == null) {
            expiresAt = timestamp.plusDays(30);
        }
        if (delivered == null) {
            delivered = false;
        }
    }
}
