package com.systemdesign.ticketmaster.service;

import com.systemdesign.ticketmaster.model.Booking;
import com.systemdesign.ticketmaster.model.Event;
import com.systemdesign.ticketmaster.model.Seat;
import com.systemdesign.ticketmaster.repository.BookingRepository;
import com.systemdesign.ticketmaster.repository.EventRepository;
import com.systemdesign.ticketmaster.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class HoldExpiryService {

    private final SeatRepository seatRepository;
    private final BookingRepository bookingRepository;
    private final EventRepository eventRepository;

    @Scheduled(fixedRate = 30000)
    @Transactional
    public void releaseExpiredHolds() {
        Instant now = Instant.now();

        List<Seat> expiredSeats = seatRepository
            .findByStatusAndHoldExpiresAtBefore(Seat.SeatStatus.HELD, now);

        if (expiredSeats.isEmpty()) {
            return;
        }

        log.info("Releasing {} expired seat holds", expiredSeats.size());

        Map<String, List<Seat>> seatsByEvent = expiredSeats.stream()
            .collect(Collectors.groupingBy(seat -> seat.getEvent().getId()));

        for (Map.Entry<String, List<Seat>> entry : seatsByEvent.entrySet()) {
            String eventId = entry.getKey();
            List<Seat> seats = entry.getValue();

            for (Seat seat : seats) {
                seat.setStatus(Seat.SeatStatus.AVAILABLE);
                seat.setHeldByUserId(null);
                seat.setHoldExpiresAt(null);
            }
            seatRepository.saveAll(seats);

            Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new IllegalStateException("Event not found"));
            event.setAvailableSeats(event.getAvailableSeats() + seats.size());
            eventRepository.save(event);

            List<Booking> bookings = bookingRepository
                .findByEventIdAndStatusAndExpiresAtBefore(
                    eventId,
                    Booking.BookingStatus.PENDING,
                    now
                );

            for (Booking booking : bookings) {
                booking.setStatus(Booking.BookingStatus.EXPIRED);
            }
            bookingRepository.saveAll(bookings);

            log.info("Released {} seats for event {}", seats.size(), eventId);
        }
    }
}
