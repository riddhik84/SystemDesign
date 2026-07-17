package com.systemdesign.instagram.repository;

import com.systemdesign.instagram.model.Post;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
}
