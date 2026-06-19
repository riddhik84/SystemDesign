package com.systemdesign.whatsapp.repository;

import com.systemdesign.whatsapp.model.ChatParticipant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChatParticipantRepository extends JpaRepository<ChatParticipant, String> {
    List<ChatParticipant> findByChatIdAndActiveTrue(String chatId);

    @Query("SELECT cp FROM ChatParticipant cp WHERE cp.chat.id = :chatId AND cp.userId = :userId")
    Optional<ChatParticipant> findByChatIdAndUserId(@Param("chatId") String chatId, @Param("userId") String userId);

    @Query("SELECT cp.userId FROM ChatParticipant cp WHERE cp.chat.id = :chatId AND cp.active = true")
    List<String> findActiveUserIdsByChatId(@Param("chatId") String chatId);

    Long countByChatIdAndActiveTrue(String chatId);
}
