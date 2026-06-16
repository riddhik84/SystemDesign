package com.systemdesign.dropbox.controller;

import com.systemdesign.dropbox.service.FileSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket handler for real-time file-change sync.
 *
 * Connection lifecycle:
 *   1. Client opens ws://host/ws/sync?userId=alice
 *   2. afterConnectionEstablished:
 *      - Extracts userId from query string.
 *      - Registers a Redis MessageListener on channel "file-changes:{userId}".
 *      - Stores session in sessionMap keyed by userId.
 *   3. Redis message arrives (published by FileSyncService.publishChange):
 *      - MessageListener.onMessage() fires.
 *      - Finds the WebSocket session for the userId.
 *      - Sends the JSON payload as a TextMessage.
 *   4. afterConnectionClosed:
 *      - Removes the MessageListener from the container.
 *      - Removes session from sessionMap.
 *
 * Multiple devices per user:
 *   A more complete implementation would use a Map<String, List<WebSocketSession>>.
 *   This reference implementation uses one active session per userId for clarity.
 *   In production, each device opens its own session; the Redis listener fans out
 *   the same message to all sessions for that userId.
 *
 * Thread safety:
 *   sessionMap and listenerMap use ConcurrentHashMap. WebSocket session.sendMessage()
 *   is synchronized per session by the Spring framework.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SyncWebSocketHandler extends TextWebSocketHandler {

    private final RedisMessageListenerContainer redisListenerContainer;

    /** userId → WebSocket session */
    private final Map<String, WebSocketSession> sessionMap = new ConcurrentHashMap<>();

    /** userId → Redis MessageListener (so we can deregister on disconnect) */
    private final Map<String, MessageListener> listenerMap = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String userId = extractUserId(session);
        if (userId == null || userId.isBlank()) {
            log.warn("WebSocket connection rejected — missing userId query param");
            try {
                session.close(CloseStatus.POLICY_VIOLATION);
            } catch (IOException e) {
                log.error("Error closing WebSocket session without userId", e);
            }
            return;
        }

        sessionMap.put(userId, session);

        // Create a Redis listener that forwards messages to this WebSocket session.
        MessageListener listener = (message, pattern) -> forwardToSession(userId, message);
        listenerMap.put(userId, listener);

        String channel = FileSyncService.CHANNEL_PREFIX + userId;
        redisListenerContainer.addMessageListener(listener, new ChannelTopic(channel));

        log.info("WebSocket connected: userId={}, sessionId={}", userId, session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String userId = extractUserId(session);
        if (userId == null) {
            return;
        }

        sessionMap.remove(userId);

        MessageListener listener = listenerMap.remove(userId);
        if (listener != null) {
            String channel = FileSyncService.CHANNEL_PREFIX + userId;
            redisListenerContainer.removeMessageListener(listener, new ChannelTopic(channel));
        }

        log.info("WebSocket disconnected: userId={}, status={}", userId, status);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // Clients do not send messages in this protocol — sync is server-push only.
        // A ping/pong keep-alive could be added here if needed.
        log.debug("Received text from client (ignored): {}", message.getPayload());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("WebSocket transport error for session {}", session.getId(), exception);
    }

    // ---------------------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------------------

    private void forwardToSession(String userId, Message message) {
        WebSocketSession session = sessionMap.get(userId);
        if (session == null || !session.isOpen()) {
            log.debug("No open session for userId={}, dropping message", userId);
            return;
        }
        try {
            String payload = new String(message.getBody());
            session.sendMessage(new TextMessage(payload));
            log.debug("Forwarded Redis message to WebSocket: userId={}", userId);
        } catch (IOException e) {
            log.error("Failed to send WebSocket message to userId={}", userId, e);
        }
    }

    /**
     * Extracts the userId query parameter from the WebSocket handshake URI.
     * Example URI: ws://host/ws/sync?userId=alice
     */
    private String extractUserId(WebSocketSession session) {
        URI uri = session.getUri();
        if (uri == null) {
            return null;
        }
        String query = uri.getQuery();
        if (query == null) {
            return null;
        }
        for (String param : query.split("&")) {
            String[] parts = param.split("=", 2);
            if (parts.length == 2 && "userId".equals(parts[0])) {
                return parts[1];
            }
        }
        return null;
    }
}
