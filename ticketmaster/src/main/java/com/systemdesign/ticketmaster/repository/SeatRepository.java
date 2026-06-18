package com.systemdesign.ticketmaster.repository;

import com.systemdesign.ticketmaster.model.Seat;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface SeatRepository extends JpaRepository<Seat, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Seat s WHERE s.id IN :ids")
    List<Seat> findByIdInWithLock(@Param("ids") List<String> ids);

    List<Seat> findByEventIdAndStatus(String eventId, Seat.SeatStatus status);

    List<Seat> findByStatusAndHoldExpiresAtBefore(
        Seat.SeatStatus status,
        Instant before
    );

    @Query("SELECT s FROM Seat s JOIN s.event e WHERE e.id = :eventId AND s.section.id = :sectionId ORDER BY s.rowNumber, s.seatNumber")
    List<Seat> findByEventIdAndSectionIdOrdered(
        @Param("eventId") String eventId,
        @Param("sectionId") String sectionId
    );
}
