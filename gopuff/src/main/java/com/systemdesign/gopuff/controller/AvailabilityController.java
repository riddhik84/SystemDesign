package com.systemdesign.gopuff.controller;

import com.systemdesign.gopuff.model.AvailabilityRequest;
import com.systemdesign.gopuff.model.AvailabilityResponse;
import com.systemdesign.gopuff.service.AvailabilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * {@code GET /availability} — which of the requested items can be delivered to the
 * caller's location.
 */
@RestController
@Tag(name = "Availability", description = "Check item availability for a delivery location")
public class AvailabilityController {

    private final AvailabilityService availabilityService;

    public AvailabilityController(AvailabilityService availabilityService) {
        this.availabilityService = availabilityService;
    }

    @GetMapping("/availability")
    @Operation(summary = "Check availability of items near a location")
    public ResponseEntity<AvailabilityResponse> getAvailability(
            @RequestParam double latitude,
            @RequestParam double longitude,
            @RequestParam List<String> items,
            @RequestParam(defaultValue = "0") int page) {

        AvailabilityRequest req = AvailabilityRequest.builder()
                .latitude(latitude)
                .longitude(longitude)
                .itemIds(items)
                .page(page)
                .build();
        return ResponseEntity.ok(availabilityService.getAvailability(req));
    }
}
