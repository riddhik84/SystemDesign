package com.systemdesign.newsfeed.repository;

import com.systemdesign.newsfeed.model.Post;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Repository
public interface PostRepository extends JpaRepository<Post, String> {

    /**
     * Retrieve recent posts by a single author in reverse chronological order.
     * Used for user profile page and feed fallback.
     */
    List<Post> findByAuthorIdOrderByCreatedAtDesc(String authorId, Pageable pageable);

    /**
     * Retrieve recent posts by multiple authors in reverse chronological order.
     * Core query for feed merge: combines posts from all followed users.
     */
    List<Post> findByAuthorIdInOrderByCreatedAtDesc(Collection<String> authorIds, Pageable pageable);

    /**
     * Retrieve recent posts by multiple authors after a given timestamp.
     * Used for fanout-on-read: fetch celebrity posts from the last N hours.
     */
    List<Post> findByAuthorIdInAndCreatedAtAfterOrderByCreatedAtDesc(
        Collection<String> authorIds,
        LocalDateTime since,
        Pageable pageable
    );

    /**
     * Atomic increment/decrement of like count.
     * Called by PostService when a post is liked; drives the ranking engagement signal.
     */
    @Modifying
    @Transactional
    @Query("UPDATE Post p SET p.likeCount = p.likeCount + :delta WHERE p.id = :id")
    void incrementLikeCount(@Param("id") String id, @Param("delta") long delta);

    /**
     * Atomic increment/decrement of comment count.
     * Called by PostService when a post is commented on; drives the ranking engagement signal.
     */
    @Modifying
    @Transactional
    @Query("UPDATE Post p SET p.commentCount = p.commentCount + :delta WHERE p.id = :id")
    void incrementCommentCount(@Param("id") String id, @Param("delta") long delta);
}
