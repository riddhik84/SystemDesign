package com.systemdesign.whatsapp.repository;

import com.systemdesign.whatsapp.model.Chat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChatRepository extends JpaRepository<Chat, String> {
    @Query("SELECT c FROM Chat c JOIN c.participants p WHERE p.userId = :userId AND p.active = true")
    List<Chat> findByUserId(@Param("userId") String userId);

    @Query("SELECT c FROM Chat c WHERE c.type = 'DIRECT' AND c.id IN " +
           "(SELECT cp1.chat.id FROM ChatParticipant cp1 WHERE cp1.userId = :user1Id) AND c.id IN " +
           "(SELECT cp2.chat.id FROM ChatParticipant cp2 WHERE cp2.userId = :user2Id)")
    List<Chat> findDirectChatBetweenUsers(@Param("user1Id") String user1Id, @Param("user2Id") String user2Id);
}
