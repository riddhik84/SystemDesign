package com.systemdesign.dropbox.config;

import com.systemdesign.dropbox.controller.SyncWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Registers the SyncWebSocketHandler at /ws/sync.
 *
 * Using raw WebSocket (not STOMP) because:
 * - Simpler mental model for interviewers — no STOMP broker needed.
 * - Direct control over message format (plain JSON FileChangeEvent).
 * - Easier to explain in a system design: "clients open a WebSocket, we push JSON events".
 *
 * The userId is passed as a query parameter: ws://host/ws/sync?userId=alice
 * In production this would be a JWT or session cookie — the query param
 * approach is intentionally simplified for this reference implementation.
 *
 * setAllowedOrigins("*") is acceptable here for a reference implementation;
 * production should restrict to known origins.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final SyncWebSocketHandler syncWebSocketHandler;

    public WebSocketConfig(SyncWebSocketHandler syncWebSocketHandler) {
        this.syncWebSocketHandler = syncWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(syncWebSocketHandler, "/ws/sync")
                .setAllowedOrigins("*");
    }
}
