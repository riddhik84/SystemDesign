package com.systemdesign.ticketmaster.repository;

import com.systemdesign.ticketmaster.model.Booking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface BookingRepository extends JpaRepository<Booking, String> {

    List<Booking> findByUserId(String userId);

    List<Booking> findByStatusAndExpiresAtBefore(
        Booking.BookingStatus status,
        Instant before
    );

    List<Booking> findByEventIdAndStatusAndExpiresAtBefore(
        String eventId,
        Booking.BookingStatus status,
        Instant before
    );
}
