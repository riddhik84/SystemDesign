package com.systemdesign.whatsapp.repository;

import com.systemdesign.whatsapp.model.Message;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface MessageRepository extends JpaRepository<Message, String> {
    List<Message> findByChatIdOrderByTimestampDesc(String chatId, Pageable pageable);

    @Query("SELECT MAX(m.sequenceNumber) FROM Message m WHERE m.senderId = :userId")
    Long findMaxSequenceNumberByUserId(@Param("userId") String userId);

    @Modifying
    @Query("DELETE FROM Message m WHERE m.expiresAt < :now")
    int deleteExpiredMessages(@Param("now") LocalDateTime now);

    @Query("SELECT m FROM Message m WHERE m.chatId = :chatId AND m.timestamp >= :since ORDER BY m.timestamp")
    List<Message> findByChatIdAndTimestampAfter(@Param("chatId") String chatId, @Param("since") LocalDateTime since);
}
