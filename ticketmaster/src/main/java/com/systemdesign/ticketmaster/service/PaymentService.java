package com.systemdesign.ticketmaster.service;

import com.systemdesign.ticketmaster.dto.BookingResponse;
import com.systemdesign.ticketmaster.dto.PaymentRequest;
import com.systemdesign.ticketmaster.model.Booking;
import com.systemdesign.ticketmaster.model.BookedSeat;
import com.systemdesign.ticketmaster.model.Event;
import com.systemdesign.ticketmaster.model.Seat;
import com.systemdesign.ticketmaster.repository.BookingRepository;
import com.systemdesign.ticketmaster.repository.EventRepository;
import com.systemdesign.ticketmaster.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final BookingRepository bookingRepository;
    private final SeatRepository seatRepository;
    private final EventRepository eventRepository;

    @Transactional
    public BookingResponse confirmBooking(String bookingId, PaymentRequest paymentRequest) {
        log.info("Processing payment for booking {}", bookingId);

        Booking booking = bookingRepository.findById(bookingId)
            .orElseThrow(() -> new IllegalArgumentException("Booking not found"));

        if (booking.getStatus() != Booking.BookingStatus.PENDING) {
            throw new IllegalStateException("Booking is not in pending state");
        }

        if (Instant.now().isAfter(booking.getExpiresAt())) {
            throw new IllegalStateException("Booking has expired");
        }

        if (!booking.getTotalAmount().equals(paymentRequest.getAmount())) {
            throw new IllegalArgumentException("Payment amount does not match booking amount");
        }

        try {
            String paymentId = processPayment(paymentRequest, bookingId);

            booking.setStatus(Booking.BookingStatus.CONFIRMED);
            booking.setPaymentId(paymentId);
            booking.setPaymentMethod(paymentRequest.getPaymentMethod());
            booking.setConfirmedAt(Instant.now());
            bookingRepository.save(booking);

            List<Seat> seats = booking.getSeats().stream()
                .map(BookedSeat::getSeat)
                .peek(seat -> {
                    seat.setStatus(Seat.SeatStatus.BOOKED);
                    seat.setHeldByUserId(null);
                    seat.setHoldExpiresAt(null);
                })
                .collect(Collectors.toList());

            seatRepository.saveAll(seats);

            log.info("Successfully confirmed booking {} with payment {}", bookingId, paymentId);

            return BookingResponse.builder()
                .bookingId(booking.getId())
                .status(booking.getStatus())
                .totalAmount(booking.getTotalAmount())
                .paymentId(paymentId)
                .confirmedAt(booking.getConfirmedAt())
                .build();

        } catch (Exception e) {
            log.error("Payment failed for booking {}: {}", bookingId, e.getMessage());

            releaseSeats(booking);

            booking.setStatus(Booking.BookingStatus.FAILED);
            bookingRepository.save(booking);

            throw new RuntimeException("Payment failed: " + e.getMessage());
        }
    }

    private String processPayment(PaymentRequest request, String idempotencyKey) {
        log.info("Processing {} payment with token {}", request.getPaymentMethod(), request.getPaymentToken());

        return "pay_" + idempotencyKey.substring(0, 8);
    }

    private void releaseSeats(Booking booking) {
        List<Seat> seats = booking.getSeats().stream()
            .map(BookedSeat::getSeat)
            .peek(seat -> {
                seat.setStatus(Seat.SeatStatus.AVAILABLE);
                seat.setHeldByUserId(null);
                seat.setHoldExpiresAt(null);
            })
            .collect(Collectors.toList());

        seatRepository.saveAll(seats);

        Event event = booking.getEvent();
        event.setAvailableSeats(event.getAvailableSeats() + seats.size());
        eventRepository.save(event);

        log.info("Released {} seats from failed booking {}", seats.size(), booking.getId());
    }
}
