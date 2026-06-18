package com.systemdesign.tinder.repository;

import com.systemdesign.tinder.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UserRepository extends JpaRepository<User, String> {

    @Query(value = """
        SELECT u.* FROM users u
        WHERE u.active = true
        AND u.id != :userId
        AND u.age BETWEEN :ageMin AND :ageMax
        AND (:interestedIn = 'NON_BINARY' OR u.gender = CAST(:interestedIn AS text))
        AND u.id NOT IN (
            SELECT s.target_user_id FROM swipes s WHERE s.swiper_id = :userId
        )
        AND (
            6371 * acos(
                cos(radians(:latitude)) * cos(radians(u.latitude)) *
                cos(radians(u.longitude) - radians(:longitude)) +
                sin(radians(:latitude)) * sin(radians(u.latitude))
            )
        ) <= :maxDistance
        ORDER BY RANDOM()
        LIMIT :limit
        """, nativeQuery = true)
    List<User> findPotentialMatches(
        @Param("userId") String userId,
        @Param("latitude") Double latitude,
        @Param("longitude") Double longitude,
        @Param("maxDistance") Integer maxDistance,
        @Param("ageMin") Integer ageMin,
        @Param("ageMax") Integer ageMax,
        @Param("interestedIn") String interestedIn,
        @Param("limit") Integer limit
    );
}
