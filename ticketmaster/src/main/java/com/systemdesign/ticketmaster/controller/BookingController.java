package com.systemdesign.ticketmaster.controller;

import com.systemdesign.ticketmaster.dto.BookingResponse;
import com.systemdesign.ticketmaster.dto.HoldRequest;
import com.systemdesign.ticketmaster.dto.PaymentRequest;
import com.systemdesign.ticketmaster.service.BookingService;
import com.systemdesign.ticketmaster.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/bookings")
@RequiredArgsConstructor
@Tag(name = "Bookings", description = "Ticket booking and reservation APIs")
public class BookingController {

    private final BookingService bookingService;
    private final PaymentService paymentService;

    @PostMapping("/hold")
    @Operation(summary = "Hold seats",
               description = "Temporarily reserve seats for a specified duration")
    public ResponseEntity<BookingResponse> holdSeats(@Valid @RequestBody HoldRequest request) {
        BookingResponse response = bookingService.holdSeats(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{bookingId}")
    @Operation(summary = "Get booking details",
               description = "Retrieve booking information and status")
    public ResponseEntity<BookingResponse> getBooking(@PathVariable String bookingId) {
        BookingResponse response = bookingService.getBooking(bookingId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{bookingId}/confirm")
    @Operation(summary = "Confirm booking",
               description = "Complete payment and confirm the booking")
    public ResponseEntity<BookingResponse> confirmBooking(
            @PathVariable String bookingId,
            @Valid @RequestBody PaymentRequest paymentRequest) {

        BookingResponse response = paymentService.confirmBooking(bookingId, paymentRequest);
        return ResponseEntity.ok(response);
    }
}
