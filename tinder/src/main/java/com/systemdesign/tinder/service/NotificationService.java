package com.systemdesign.tinder.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    @Async
    public void sendMatchNotification(String userId, String matchedUserId, String matchId) {
        log.info("Sending match notification to user {} about match {} with user {}",
            userId, matchId, matchedUserId);

        try {
            simulatePushNotification(userId, matchedUserId, matchId);
        } catch (Exception e) {
            log.error("Failed to send notification to user {}: {}", userId, e.getMessage());
        }
    }

    private void simulatePushNotification(String userId, String matchedUserId, String matchId) {
        log.info("PUSH NOTIFICATION -> User {}: You matched with user {}! Match ID: {}",
            userId, matchedUserId, matchId);
    }
}
