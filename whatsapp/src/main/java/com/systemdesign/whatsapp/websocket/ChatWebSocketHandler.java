package com.systemdesign.whatsapp.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.systemdesign.whatsapp.service.ChatService;
import com.systemdesign.whatsapp.service.MessageService;
import com.systemdesign.whatsapp.service.WebSocketConnectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
@RequiredArgsConstructor
@Slf4j
public class ChatWebSocketHandler extends TextWebSocketHandler {
    private final WebSocketConnectionService connectionService;
    private final MessageService messageService;
    private final ChatService chatService;
    private final ObjectMapper objectMapper;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String userId = extractUserId(session);
        String clientId = connectionService.registerConnection(userId, session);
        log.info("WebSocket connection established: userId={}, clientId={}, sessionId={}",
                 userId, clientId, session.getId());

        messageService.syncInbox(clientId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        WebSocketMessage wsMessage = objectMapper.readValue(payload, WebSocketMessage.class);

        String userId = extractUserId(session);
        String clientId = connectionService.getClientId(session.getId());

        log.debug("Received message: command={}, userId={}, clientId={}",
                  wsMessage.getCommand(), userId, clientId);

        switch (wsMessage.getCommand()) {
            case CREATE_CHAT:
                handleCreateChat(session, wsMessage, userId);
                break;
            case SEND_MESSAGE:
                handleSendMessage(session, wsMessage, userId, clientId);
                break;
            case MODIFY_CHAT_PARTICIPANTS:
                handleModifyParticipants(session, wsMessage, userId);
                break;
            case ACK_MESSAGE:
                handleAckMessage(session, wsMessage, clientId);
                break;
            case SYNC_INBOX:
                handleSyncInbox(session, clientId);
                break;
            case HEARTBEAT:
                handleHeartbeat(session, wsMessage, clientId);
                break;
            default:
                sendError(session, "Unknown command: " + wsMessage.getCommand());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String userId = extractUserId(session);
        String clientId = connectionService.getClientId(session.getId());

        connectionService.unregisterConnection(session.getId());
        log.info("WebSocket connection closed: userId={}, clientId={}, status={}",
                 userId, clientId, status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("WebSocket transport error: sessionId={}", session.getId(), exception);
        session.close(CloseStatus.SERVER_ERROR);
    }

    private void handleCreateChat(WebSocketSession session, WebSocketMessage wsMessage, String userId) {
        try {
            WebSocketMessage.CreateChatRequest request = objectMapper.convertValue(
                wsMessage.getPayload(), WebSocketMessage.CreateChatRequest.class);

            String chatId = chatService.createChat(request.getParticipants(), request.getName(), userId);

            WebSocketMessage response = WebSocketMessage.builder()
                .command(WebSocketMessage.MessageCommand.CHAT_UPDATE)
                .payload(Map.of("chatId", chatId, "status", "CREATED"))
                .build();

            sendMessage(session, response);
        } catch (Exception e) {
            log.error("Error creating chat", e);
            sendError(session, "Failed to create chat: " + e.getMessage());
        }
    }

    private void handleSendMessage(WebSocketSession session, WebSocketMessage wsMessage,
                                   String userId, String clientId) {
        try {
            WebSocketMessage.SendMessageRequest request = objectMapper.convertValue(
                wsMessage.getPayload(), WebSocketMessage.SendMessageRequest.class);

            String messageId = messageService.sendMessage(
                request.getChatId(),
                userId,
                request.getMessage(),
                request.getMessageType(),
                request.getAttachments()
            );

            WebSocketMessage response = WebSocketMessage.builder()
                .command(WebSocketMessage.MessageCommand.MESSAGE_SENT)
                .messageId(messageId)
                .payload(Map.of("status", "SUCCESS", "chatId", request.getChatId()))
                .build();

            sendMessage(session, response);
        } catch (Exception e) {
            log.error("Error sending message", e);
            sendError(session, "Failed to send message: " + e.getMessage());
        }
    }

    private void handleModifyParticipants(WebSocketSession session, WebSocketMessage wsMessage, String userId) {
        try {
            WebSocketMessage.ModifyParticipantsRequest request = objectMapper.convertValue(
                wsMessage.getPayload(), WebSocketMessage.ModifyParticipantsRequest.class);

            if ("ADD".equals(request.getOperation())) {
                chatService.addParticipant(request.getChatId(), request.getUserId());
            } else if ("REMOVE".equals(request.getOperation())) {
                chatService.removeParticipant(request.getChatId(), request.getUserId());
            }

            WebSocketMessage response = WebSocketMessage.builder()
                .command(WebSocketMessage.MessageCommand.CHAT_UPDATE)
                .payload(Map.of("chatId", request.getChatId(), "status", "UPDATED"))
                .build();

            sendMessage(session, response);
        } catch (Exception e) {
            log.error("Error modifying participants", e);
            sendError(session, "Failed to modify participants: " + e.getMessage());
        }
    }

    private void handleAckMessage(WebSocketSession session, WebSocketMessage wsMessage, String clientId) {
        try {
            WebSocketMessage.AckMessageRequest request = objectMapper.convertValue(
                wsMessage.getPayload(), WebSocketMessage.AckMessageRequest.class);

            messageService.acknowledgeMessage(clientId, request.getMessageId());
            log.debug("Message acknowledged: clientId={}, messageId={}", clientId, request.getMessageId());
        } catch (Exception e) {
            log.error("Error acknowledging message", e);
        }
    }

    private void handleSyncInbox(WebSocketSession session, String clientId) {
        try {
            messageService.syncInbox(clientId);
        } catch (Exception e) {
            log.error("Error syncing inbox", e);
            sendError(session, "Failed to sync inbox: " + e.getMessage());
        }
    }

    private void handleHeartbeat(WebSocketSession session, WebSocketMessage wsMessage, String clientId) {
        try {
            connectionService.updateHeartbeat(clientId);

            WebSocketMessage response = WebSocketMessage.builder()
                .command(WebSocketMessage.MessageCommand.HEARTBEAT_RESPONSE)
                .sequenceNumber(wsMessage.getSequenceNumber())
                .build();

            sendMessage(session, response);
        } catch (Exception e) {
            log.error("Error handling heartbeat", e);
        }
    }

    private void sendMessage(WebSocketSession session, WebSocketMessage message) {
        try {
            String payload = objectMapper.writeValueAsString(message);
            session.sendMessage(new TextMessage(payload));
        } catch (Exception e) {
            log.error("Error sending WebSocket message", e);
        }
    }

    private void sendError(WebSocketSession session, String errorMessage) {
        WebSocketMessage error = WebSocketMessage.builder()
            .command(WebSocketMessage.MessageCommand.ERROR)
            .payload(Map.of("error", errorMessage))
            .build();
        sendMessage(session, error);
    }

    private String extractUserId(WebSocketSession session) {
        return (String) session.getAttributes().get("userId");
    }
}
