package com.systemdesign.ticketmaster.service;

import com.systemdesign.ticketmaster.model.Event;
import com.systemdesign.ticketmaster.model.WaitingRoomEntry;
import com.systemdesign.ticketmaster.repository.EventRepository;
import com.systemdesign.ticketmaster.repository.WaitingRoomRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class WaitingRoomService {

    private final WaitingRoomRepository waitingRoomRepository;
    private final EventRepository eventRepository;
    private final RedisTemplate<String, String> redisTemplate;

    private static final int BATCH_SIZE = 100;

    @Transactional
    public WaitingRoomEntry joinQueue(String eventId, String userId, String sessionId) {
        Event event = eventRepository.findById(eventId)
            .orElseThrow(() -> new IllegalArgumentException("Event not found"));

        if (!event.getRequiresWaitingRoom()) {
            throw new IllegalStateException("Event does not require waiting room");
        }

        String queueKey = "queue:" + eventId;
        long timestamp = System.currentTimeMillis();

        redisTemplate.opsForZSet().add(queueKey, sessionId, timestamp);

        Long position = redisTemplate.opsForZSet().rank(queueKey, sessionId);

        WaitingRoomEntry entry = WaitingRoomEntry.builder()
            .id(UUID.randomUUID().toString())
            .event(event)
            .userId(userId)
            .sessionId(sessionId)
            .queuePosition(position != null ? position.intValue() + 1 : 1)
            .joinedAt(Instant.now())
            .status(WaitingRoomEntry.QueueStatus.WAITING)
            .build();

        return waitingRoomRepository.save(entry);
    }

    @Transactional(readOnly = true)
    public WaitingRoomEntry getQueueStatus(String sessionId) {
        return waitingRoomRepository.findBySessionId(sessionId)
            .orElseThrow(() -> new IllegalArgumentException("Session not found in queue"));
    }

    @Scheduled(fixedRate = 10000)
    @Transactional
    public void admitUsers() {
        List<Event> highDemandEvents = eventRepository
            .findByRequiresWaitingRoomTrueAndStatus(Event.EventStatus.ON_SALE);

        for (Event event : highDemandEvents) {
            String queueKey = "queue:" + event.getId();

            Set<String> admitted = redisTemplate.opsForZSet()
                .range(queueKey, 0, BATCH_SIZE - 1);

            if (admitted == null || admitted.isEmpty()) {
                continue;
            }

            for (String sessionId : admitted) {
                String token = generateAccessToken(sessionId, event.getId());

                redisTemplate.opsForValue().set(
                    "access:" + sessionId,
                    token,
                    Duration.ofMinutes(30)
                );

                WaitingRoomEntry entry = waitingRoomRepository
                    .findByEventIdAndSessionId(event.getId(), sessionId)
                    .orElse(null);

                if (entry != null) {
                    entry.setStatus(WaitingRoomEntry.QueueStatus.ALLOWED);
                    entry.setAllowedAt(Instant.now());
                    waitingRoomRepository.save(entry);
                }
            }

            redisTemplate.opsForZSet().removeRange(queueKey, 0, BATCH_SIZE - 1);

            log.info("Admitted {} users for event {}", admitted.size(), event.getId());
        }
    }

    public boolean hasAccess(String eventId, String sessionId) {
        String token = redisTemplate.opsForValue().get("access:" + sessionId);
        return token != null;
    }

    private String generateAccessToken(String sessionId, String eventId) {
        return UUID.randomUUID().toString();
    }
}
