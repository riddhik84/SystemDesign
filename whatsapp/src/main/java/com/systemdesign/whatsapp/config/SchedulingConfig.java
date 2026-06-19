package com.systemdesign.whatsapp.config;

import com.systemdesign.whatsapp.service.MessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class SchedulingConfig {
    private final MessageService messageService;

    @Scheduled(cron = "0 0 2 * * ?")
    public void cleanupExpiredData() {
        log.info("Starting scheduled cleanup of expired messages");
        messageService.cleanupExpiredMessages();
    }
}
