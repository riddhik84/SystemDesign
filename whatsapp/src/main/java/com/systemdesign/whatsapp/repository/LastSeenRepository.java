package com.systemdesign.whatsapp.repository;

import com.systemdesign.whatsapp.model.LastSeen;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public interface LastSeenRepository extends JpaRepository<LastSeen, String> {
    @Modifying
    @Query("UPDATE LastSeen ls SET ls.lastSeenAt = :timestamp, ls.online = false WHERE ls.userId = :userId")
    int updateLastSeen(@Param("userId") String userId, @Param("timestamp") LocalDateTime timestamp);
}
