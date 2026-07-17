package com.systemdesign.newsfeed.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "users", indexes = {
    @Index(name = "idx_user_username", columnList = "username")
})
@Data
@NoArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false)
    private String displayName;

    @Column(nullable = false)
    private String email;

    @Column(length = 500)
    private String bio;

    private String profilePictureUrl;

    @Column(nullable = false)
    private long followerCount = 0;

    @Column(nullable = false)
    private long followingCount = 0;

    @Column(nullable = false)
    private long postCount = 0;

    @Column(name = "is_celebrity", nullable = false)
    private boolean celebrity = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
