package com.systemdesign.ticketmaster.controller;

import com.systemdesign.ticketmaster.dto.EventResponse;
import com.systemdesign.ticketmaster.service.EventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
@Tag(name = "Events", description = "Event browsing and discovery APIs")
public class EventController {

    private final EventService eventService;

    @GetMapping
    @Operation(summary = "Browse events",
               description = "Search events by city, date, and category")
    public ResponseEntity<Page<EventResponse>> browseEvents(
            @RequestParam String city,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Page<EventResponse> events = eventService.browseEvents(
            city,
            category,
            PageRequest.of(page, Math.min(size, 100))
        );

        return ResponseEntity.ok(events);
    }

    @GetMapping("/{eventId}")
    @Operation(summary = "Get event details",
               description = "Retrieve detailed information about a specific event")
    public ResponseEntity<EventResponse> getEvent(@PathVariable String eventId) {
        EventResponse event = eventService.getEvent(eventId);
        return ResponseEntity.ok(event);
    }
}
