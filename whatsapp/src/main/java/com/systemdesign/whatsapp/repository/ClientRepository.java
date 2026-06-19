package com.systemdesign.whatsapp.repository;

import com.systemdesign.whatsapp.model.Client;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ClientRepository extends JpaRepository<Client, String> {
    Optional<Client> findBySessionId(String sessionId);

    List<Client> findByUserIdAndOnlineTrue(String userId);

    List<Client> findByUserId(String userId);

    Long countByUserId(String userId);

    @Modifying
    @Query("UPDATE Client c SET c.online = false, c.lastSeenAt = CURRENT_TIMESTAMP WHERE c.id = :clientId")
    int markOffline(@Param("clientId") String clientId);

    @Modifying
    @Query("UPDATE Client c SET c.lastSequenceNumber = :sequenceNumber WHERE c.id = :clientId")
    int updateSequenceNumber(@Param("clientId") String clientId, @Param("sequenceNumber") Long sequenceNumber);
}
