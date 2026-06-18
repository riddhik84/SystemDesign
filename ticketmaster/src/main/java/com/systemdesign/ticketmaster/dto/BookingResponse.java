package com.systemdesign.ticketmaster.dto;

import com.systemdesign.ticketmaster.model.Booking;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookingResponse {
    private String bookingId;
    private Booking.BookingStatus status;
    private List<SeatInfo> seats;
    private BigDecimal totalAmount;
    private Instant expiresAt;
    private Integer holdDurationSeconds;
    private String paymentId;
    private Instant confirmedAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SeatInfo {
        private String seatId;
        private String seatNumber;
        private String section;
        private BigDecimal price;
    }
}
