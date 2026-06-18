package com.systemdesign.ticketmaster.service;

import com.systemdesign.ticketmaster.dto.BookingResponse;
import com.systemdesign.ticketmaster.dto.HoldRequest;
import com.systemdesign.ticketmaster.dto.PaymentRequest;
import com.systemdesign.ticketmaster.model.*;
import com.systemdesign.ticketmaster.repository.BookingRepository;
import com.systemdesign.ticketmaster.repository.EventRepository;
import com.systemdesign.ticketmaster.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookingService {

    private final EventRepository eventRepository;
    private final SeatRepository seatRepository;
    private final BookingRepository bookingRepository;
    private final RedisLockService redisLockService;

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public BookingResponse holdSeats(HoldRequest request) {
        log.info("Processing hold request for event {} by user {}", request.getEventId(), request.getUserId());

        Event event = eventRepository.findById(request.getEventId())
            .orElseThrow(() -> new IllegalArgumentException("Event not found"));

        if (event.getStatus() != Event.EventStatus.ON_SALE) {
            throw new IllegalStateException("Event is not on sale");
        }

        String lockKey = "booking:event:" + event.getId();

        return redisLockService.executeWithLock(lockKey, 10, () -> {
            List<Seat> seats = seatRepository.findByIdInWithLock(request.getSeatIds());

            if (seats.size() != request.getSeatIds().size()) {
                throw new IllegalArgumentException("One or more seats not found");
            }

            for (Seat seat : seats) {
                if (seat.getStatus() != Seat.SeatStatus.AVAILABLE) {
                    throw new IllegalStateException("Seat " + seat.getSeatNumber() + " is not available");
                }
            }

            Instant holdExpiry = Instant.now().plus(event.getHoldTimeMinutes(), ChronoUnit.MINUTES);

            for (Seat seat : seats) {
                seat.setStatus(Seat.SeatStatus.HELD);
                seat.setHeldByUserId(request.getUserId());
                seat.setHoldExpiresAt(holdExpiry);
            }
            seatRepository.saveAll(seats);

            BigDecimal totalAmount = seats.stream()
                .map(Seat::getPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

            Booking booking = Booking.builder()
                .id(UUID.randomUUID().toString())
                .userId(request.getUserId())
                .event(event)
                .totalAmount(totalAmount)
                .status(Booking.BookingStatus.PENDING)
                .expiresAt(holdExpiry)
                .build();

            List<BookedSeat> bookedSeats = seats.stream()
                .map(seat -> BookedSeat.builder()
                    .id(UUID.randomUUID().toString())
                    .booking(booking)
                    .seat(seat)
                    .price(seat.getPrice())
                    .build())
                .collect(Collectors.toList());

            booking.setSeats(bookedSeats);
            bookingRepository.save(booking);

            event.setAvailableSeats(event.getAvailableSeats() - seats.size());
            eventRepository.save(event);

            log.info("Successfully held {} seats for booking {}", seats.size(), booking.getId());

            return buildResponse(booking, seats, event);
        });
    }

    @Transactional(readOnly = true)
    public BookingResponse getBooking(String bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
            .orElseThrow(() -> new IllegalArgumentException("Booking not found"));

        List<Seat> seats = booking.getSeats().stream()
            .map(BookedSeat::getSeat)
            .collect(Collectors.toList());

        return buildResponse(booking, seats, booking.getEvent());
    }

    private BookingResponse buildResponse(Booking booking, List<Seat> seats, Event event) {
        List<BookingResponse.SeatInfo> seatInfos = seats.stream()
            .map(seat -> BookingResponse.SeatInfo.builder()
                .seatId(seat.getId())
                .seatNumber(seat.getSeatNumber())
                .section(seat.getSection().getName())
                .price(seat.getPrice())
                .build())
            .collect(Collectors.toList());

        long holdDuration = ChronoUnit.SECONDS.between(Instant.now(), booking.getExpiresAt());

        return BookingResponse.builder()
            .bookingId(booking.getId())
            .status(booking.getStatus())
            .seats(seatInfos)
            .totalAmount(booking.getTotalAmount())
            .expiresAt(booking.getExpiresAt())
            .holdDurationSeconds((int) Math.max(0, holdDuration))
            .paymentId(booking.getPaymentId())
            .confirmedAt(booking.getConfirmedAt())
            .build();
    }
}
