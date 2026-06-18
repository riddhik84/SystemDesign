package com.systemdesign.tinder.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "matches",
    indexes = {
        @Index(name = "idx_user1_user2", columnList = "user1Id,user2Id", unique = true),
        @Index(name = "idx_user1_timestamp", columnList = "user1Id,createdAt"),
        @Index(name = "idx_user2_timestamp", columnList = "user2Id,createdAt")
    },
    uniqueConstraints = {
        @UniqueConstraint(columnNames = {"user1Id", "user2Id"})
    }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Match {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @NotNull
    @Column(nullable = false)
    private String user1Id;

    @NotNull
    @Column(nullable = false)
    private String user2Id;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(nullable = false)
    private Boolean active = true;
}
