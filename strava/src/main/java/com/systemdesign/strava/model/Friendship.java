package com.systemdesign.strava.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A directed social edge from {@code userId} to {@code friendId}. Mutual friendships are stored as
 * two rows (both directions) so friend lookups are a single indexed read on {@code user_id}, which
 * feeds the activity feed and live-friends views.
 */
@Entity
@Table(name = "friendships",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_friend", columnNames = {"user_id", "friend_id"})
    },
    indexes = {
        @Index(name = "idx_friend_user", columnList = "user_id")
    }
)
@Data
@NoArgsConstructor
public class Friendship {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "friend_id")
    private String friendId;
}
