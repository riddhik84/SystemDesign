package com.systemdesign.ticketmaster.dto;

import com.systemdesign.ticketmaster.model.Event;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventResponse {
    private String id;
    private String name;
    private String description;
    private String category;
    private VenueInfo venue;
    private Instant eventDate;
    private Instant saleStartDate;
    private Event.EventStatus status;
    private Integer availableSeats;
    private Integer totalSeats;
    private PriceRange priceRange;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VenueInfo {
        private String id;
        private String name;
        private String city;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PriceRange {
        private BigDecimal min;
        private BigDecimal max;
    }
}
