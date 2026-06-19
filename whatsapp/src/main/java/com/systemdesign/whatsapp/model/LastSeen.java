package com.systemdesign.whatsapp.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "last_seen")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LastSeen {
    @Id
    private String userId;

    @Column(nullable = false)
    private LocalDateTime lastSeenAt;

    @Column(nullable = false)
    private Boolean online;

    @PrePersist
    @PreUpdate
    protected void onUpdate() {
        if (lastSeenAt == null) {
            lastSeenAt = LocalDateTime.now();
        }
    }
}
