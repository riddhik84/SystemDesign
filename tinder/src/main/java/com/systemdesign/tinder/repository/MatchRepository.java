package com.systemdesign.tinder.repository;

import com.systemdesign.tinder.model.Match;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MatchRepository extends JpaRepository<Match, String> {

    @Query("SELECT m FROM Match m WHERE (m.user1Id = :userId OR m.user2Id = :userId) AND m.active = true ORDER BY m.createdAt DESC")
    List<Match> findActiveMatchesByUserId(@Param("userId") String userId);

    @Query("SELECT m FROM Match m WHERE ((m.user1Id = :user1Id AND m.user2Id = :user2Id) OR (m.user1Id = :user2Id AND m.user2Id = :user1Id)) AND m.active = true")
    Optional<Match> findActiveMatchBetweenUsers(@Param("user1Id") String user1Id, @Param("user2Id") String user2Id);

    boolean existsByUser1IdAndUser2IdAndActiveTrue(String user1Id, String user2Id);
}
