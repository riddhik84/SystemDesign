package com.systemdesign.whatsapp.repository;

import com.systemdesign.whatsapp.model.Inbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface InboxRepository extends JpaRepository<Inbox, String> {
    List<Inbox> findByClientIdAndDeliveredFalseOrderByTimestamp(String clientId);

    @Modifying
    @Query("UPDATE Inbox i SET i.delivered = true WHERE i.clientId = :clientId AND i.messageId = :messageId")
    int markAsDelivered(@Param("clientId") String clientId, @Param("messageId") String messageId);

    @Modifying
    @Query("DELETE FROM Inbox i WHERE i.expiresAt < :now")
    int deleteExpiredInbox(@Param("now") LocalDateTime now);

    @Modifying
    @Query("DELETE FROM Inbox i WHERE i.clientId = :clientId AND i.messageId IN :messageIds")
    int deleteByClientIdAndMessageIds(@Param("clientId") String clientId, @Param("messageIds") List<String> messageIds);

    Long countByClientIdAndDeliveredFalse(String clientId);
}
