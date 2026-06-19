package com.systemdesign.whatsapp.service;

import com.systemdesign.whatsapp.model.Client;
import com.systemdesign.whatsapp.model.LastSeen;
import com.systemdesign.whatsapp.repository.ClientRepository;
import com.systemdesign.whatsapp.repository.LastSeenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.socket.WebSocketSession;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class WebSocketConnectionService {
    private final ClientRepository clientRepository;
    private final LastSeenRepository lastSeenRepository;

    private final Map<String, WebSocketSession> sessionMap = new ConcurrentHashMap<>();
    private final Map<String, String> sessionToClientMap = new ConcurrentHashMap<>();
    private final Map<String, String> userToSessionMap = new ConcurrentHashMap<>();

    @Transactional
    public String registerConnection(String userId, WebSocketSession session) {
        Long activeClientCount = clientRepository.countByUserId(userId);
        if (activeClientCount >= 3) {
            throw new RuntimeException("Maximum 3 clients allowed per user");
        }

        String clientId = UUID.randomUUID().toString();
        String sessionId = session.getId();

        Client client = Client.builder()
            .id(clientId)
            .userId(userId)
            .sessionId(sessionId)
            .deviceType("WEB")
            .deviceId(UUID.randomUUID().toString())
            .online(true)
            .connectedAt(LocalDateTime.now())
            .lastSequenceNumber(0L)
            .build();

        clientRepository.save(client);

        sessionMap.put(sessionId, session);
        sessionToClientMap.put(sessionId, clientId);
        userToSessionMap.put(userId, sessionId);

        LastSeen lastSeen = lastSeenRepository.findById(userId)
            .orElse(LastSeen.builder().userId(userId).build());
        lastSeen.setOnline(true);
        lastSeen.setLastSeenAt(LocalDateTime.now());
        lastSeenRepository.save(lastSeen);

        log.info("Registered connection: userId={}, clientId={}, sessionId={}", userId, clientId, sessionId);
        return clientId;
    }

    @Transactional
    public void unregisterConnection(String sessionId) {
        String clientId = sessionToClientMap.remove(sessionId);
        if (clientId == null) {
            return;
        }

        WebSocketSession session = sessionMap.remove(sessionId);
        if (session != null) {
            String userId = (String) session.getAttributes().get("userId");
            userToSessionMap.remove(userId);

            clientRepository.markOffline(clientId);

            List<Client> onlineClients = clientRepository.findByUserIdAndOnlineTrue(userId);
            if (onlineClients.isEmpty()) {
                lastSeenRepository.updateLastSeen(userId, LocalDateTime.now());
                log.info("User went offline: userId={}", userId);
            }
        }

        log.info("Unregistered connection: clientId={}, sessionId={}", clientId, sessionId);
    }

    public WebSocketSession getSession(String userId) {
        String sessionId = userToSessionMap.get(userId);
        return sessionId != null ? sessionMap.get(sessionId) : null;
    }

    public List<WebSocketSession> getAllSessions(List<String> userIds) {
        return userIds.stream()
            .map(this::getSession)
            .filter(session -> session != null)
            .toList();
    }

    public String getClientId(String sessionId) {
        return sessionToClientMap.get(sessionId);
    }

    public void updateHeartbeat(String clientId) {
        log.debug("Heartbeat received: clientId={}", clientId);
    }

    public boolean isUserOnline(String userId) {
        return userToSessionMap.containsKey(userId);
    }
}
