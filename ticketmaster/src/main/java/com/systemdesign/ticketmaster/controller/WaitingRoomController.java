package com.systemdesign.ticketmaster.controller;

import com.systemdesign.ticketmaster.model.WaitingRoomEntry;
import com.systemdesign.ticketmaster.service.WaitingRoomService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/events/{eventId}/waiting-room")
@RequiredArgsConstructor
@Tag(name = "Waiting Room", description = "Queue management for high-demand events")
public class WaitingRoomController {

    private final WaitingRoomService waitingRoomService;

    @PostMapping("/join")
    @Operation(summary = "Join waiting room",
               description = "Enter the queue for a high-demand event")
    public ResponseEntity<WaitingRoomEntry> joinQueue(
            @PathVariable String eventId,
            @RequestParam String userId,
            @RequestParam String sessionId) {

        WaitingRoomEntry entry = waitingRoomService.joinQueue(eventId, userId, sessionId);
        return ResponseEntity.ok(entry);
    }

    @GetMapping("/status")
    @Operation(summary = "Check queue status",
               description = "Get current position and estimated wait time")
    public ResponseEntity<WaitingRoomEntry> getStatus(@RequestParam String sessionId) {
        WaitingRoomEntry entry = waitingRoomService.getQueueStatus(sessionId);
        return ResponseEntity.ok(entry);
    }
}
