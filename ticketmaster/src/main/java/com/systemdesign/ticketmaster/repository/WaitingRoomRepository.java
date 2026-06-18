package com.systemdesign.ticketmaster.repository;

import com.systemdesign.ticketmaster.model.WaitingRoomEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface WaitingRoomRepository extends JpaRepository<WaitingRoomEntry, String> {

    Optional<WaitingRoomEntry> findByEventIdAndSessionId(String eventId, String sessionId);

    Optional<WaitingRoomEntry> findBySessionId(String sessionId);
}
