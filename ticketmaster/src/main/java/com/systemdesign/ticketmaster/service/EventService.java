package com.systemdesign.ticketmaster.service;

import com.systemdesign.ticketmaster.dto.EventResponse;
import com.systemdesign.ticketmaster.model.Event;
import com.systemdesign.ticketmaster.model.Seat;
import com.systemdesign.ticketmaster.repository.EventRepository;
import com.systemdesign.ticketmaster.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class EventService {

    private final EventRepository eventRepository;
    private final SeatRepository seatRepository;

    @Transactional(readOnly = true)
    public Page<EventResponse> browseEvents(String city, String category, Pageable pageable) {
        Instant now = Instant.now();

        Page<Event> events;
        if (category != null && !category.isEmpty()) {
            events = eventRepository.findByCityAndEventDateAfterAndCategory(
                city, now, category, pageable
            );
        } else {
            events = eventRepository.findByCityAndEventDateAfter(
                city, now, pageable
            );
        }

        return events.map(this::toEventResponse);
    }

    @Transactional(readOnly = true)
    public EventResponse getEvent(String eventId) {
        Event event = eventRepository.findById(eventId)
            .orElseThrow(() -> new IllegalArgumentException("Event not found"));

        return toEventResponse(event);
    }

    private EventResponse toEventResponse(Event event) {
        List<Seat> seats = seatRepository.findByEventIdAndStatus(
            event.getId(),
            Seat.SeatStatus.AVAILABLE
        );

        BigDecimal minPrice = seats.stream()
            .map(Seat::getPrice)
            .min(Comparator.naturalOrder())
            .orElse(BigDecimal.ZERO);

        BigDecimal maxPrice = seats.stream()
            .map(Seat::getPrice)
            .max(Comparator.naturalOrder())
            .orElse(BigDecimal.ZERO);

        return EventResponse.builder()
            .id(event.getId())
            .name(event.getName())
            .description(event.getDescription())
            .category(event.getCategory())
            .venue(EventResponse.VenueInfo.builder()
                .id(event.getVenue().getId())
                .name(event.getVenue().getName())
                .city(event.getVenue().getCity())
                .build())
            .eventDate(event.getEventDate())
            .saleStartDate(event.getSaleStartDate())
            .status(event.getStatus())
            .availableSeats(event.getAvailableSeats())
            .totalSeats(event.getTotalSeats())
            .priceRange(EventResponse.PriceRange.builder()
                .min(minPrice)
                .max(maxPrice)
                .build())
            .build();
    }
}
