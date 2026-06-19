package com.systemdesign.whatsapp.controller;

import com.systemdesign.whatsapp.model.LastSeen;
import com.systemdesign.whatsapp.repository.LastSeenRepository;
import com.systemdesign.whatsapp.service.WebSocketConnectionService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {
    private final WebSocketConnectionService connectionService;
    private final LastSeenRepository lastSeenRepository;

    @GetMapping("/{userId}/status")
    public ResponseEntity<UserStatusResponse> getUserStatus(@PathVariable String userId) {
        boolean online = connectionService.isUserOnline(userId);

        UserStatusResponse response = new UserStatusResponse();
        response.setUserId(userId);
        response.setOnline(online);

        if (!online) {
            LastSeen lastSeen = lastSeenRepository.findById(userId).orElse(null);
            if (lastSeen != null) {
                response.setLastSeen(lastSeen.getLastSeenAt().toString());
            }
        }

        return ResponseEntity.ok(response);
    }

    @Data
    public static class UserStatusResponse {
        private String userId;
        private Boolean online;
        private String lastSeen;
    }
}
