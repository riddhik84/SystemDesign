package com.systemdesign.whatsapp.websocket;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebSocketMessage {
    private MessageCommand command;
    private Map<String, Object> payload;
    private String messageId;
    private Long sequenceNumber;

    public enum MessageCommand {
        // Client to Server
        CREATE_CHAT,
        SEND_MESSAGE,
        MODIFY_CHAT_PARTICIPANTS,
        GET_ATTACHMENT_TARGET,
        SYNC_INBOX,
        ACK_MESSAGE,
        HEARTBEAT,

        // Server to Client
        CHAT_UPDATE,
        NEW_MESSAGE,
        MESSAGE_SENT,
        HEARTBEAT_RESPONSE,
        ERROR
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateChatRequest {
        private List<String> participants;
        private String name;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SendMessageRequest {
        private String chatId;
        private String message;
        private String messageType;
        private List<String> attachments;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ModifyParticipantsRequest {
        private String chatId;
        private String userId;
        private String operation;  // ADD or REMOVE
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AckMessageRequest {
        private String messageId;
        private String chatId;
    }
}
