package com.systemdesign.tinder.repository;

import com.systemdesign.tinder.model.Swipe;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SwipeRepository extends JpaRepository<Swipe, String> {

    Optional<Swipe> findBySwiperIdAndTargetUserId(String swiperId, String targetUserId);

    @Query("SELECT s.targetUserId FROM Swipe s WHERE s.swiperId = :userId ORDER BY s.createdAt DESC")
    List<String> findRecentSwipedUserIds(@Param("userId") String userId);

    boolean existsBySwiperIdAndTargetUserId(String swiperId, String targetUserId);

    @Query("SELECT COUNT(s) FROM Swipe s WHERE s.swiperId = :userId AND s.direction = 'RIGHT'")
    long countRightSwipesByUser(@Param("userId") String userId);
}
