package com.systemdesign.tinder.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "swipes",
    indexes = {
        @Index(name = "idx_swiper_target", columnList = "swiperId,targetUserId", unique = true),
        @Index(name = "idx_swiper_timestamp", columnList = "swiperId,createdAt"),
        @Index(name = "idx_target_direction", columnList = "targetUserId,direction")
    },
    uniqueConstraints = {
        @UniqueConstraint(columnNames = {"swiperId", "targetUserId"})
    }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Swipe {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @NotNull
    @Column(nullable = false)
    private String swiperId;

    @NotNull
    @Column(nullable = false)
    private String targetUserId;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Direction direction;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public enum Direction {
        RIGHT, LEFT
    }
}
