package com.systemdesign.ticketmaster.repository;

import com.systemdesign.ticketmaster.model.Event;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface EventRepository extends JpaRepository<Event, String> {

    Page<Event> findByCityAndEventDateAfterAndCategory(
        String city,
        Instant after,
        String category,
        Pageable pageable
    );

    Page<Event> findByCityAndEventDateAfter(
        String city,
        Instant after,
        Pageable pageable
    );

    List<Event> findByRequiresWaitingRoomTrueAndStatus(Event.EventStatus status);
}
