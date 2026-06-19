package com.systemdesign.whatsapp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.systemdesign.whatsapp.model.*;
import com.systemdesign.whatsapp.repository.*;
import com.systemdesign.whatsapp.websocket.WebSocketMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class MessageService {
    private final MessageRepository messageRepository;
    private final InboxRepository inboxRepository;
    private final ChatParticipantRepository participantRepository;
    private final ClientRepository clientRepository;
    private final WebSocketConnectionService connectionService;
    private final RedisPubSubService redisPubSubService;
    private final ObjectMapper objectMapper;

    @Transactional
    public String sendMessage(String chatId, String senderId, String content,
                              String messageType, List<String> attachmentUrls) {
        Long sequenceNumber = getNextSequenceNumber(senderId);

        Message message = Message.builder()
            .chatId(chatId)
            .senderId(senderId)
            .content(content)
            .type(Message.MessageType.valueOf(messageType.toUpperCase()))
            .timestamp(LocalDateTime.now())
            .attachmentUrls(attachmentUrls != null ? attachmentUrls : List.of())
            .sequenceNumber(sequenceNumber)
            .build();

        message = messageRepository.save(message);
        log.info("Message saved: messageId={}, chatId={}, senderId={}", message.getId(), chatId, senderId);

        List<String> participants = participantRepository.findActiveUserIdsByChatId(chatId);

        for (String participantId : participants) {
            if (!participantId.equals(senderId)) {
                List<Client> clientsForUser = clientRepository.findByUserId(participantId);
                for (Client client : clientsForUser) {
                    Inbox inboxEntry = Inbox.builder()
                        .clientId(client.getId())
                        .userId(participantId)
                        .messageId(message.getId())
                        .chatId(chatId)
                        .timestamp(LocalDateTime.now())
                        .delivered(false)
                        .build();
                    inboxRepository.save(inboxEntry);
                }
            }
        }

        redisPubSubService.publishMessage(message, participants);

        return message.getId();
    }

    @Transactional
    public void acknowledgeMessage(String clientId, String messageId) {
        int updated = inboxRepository.markAsDelivered(clientId, messageId);
        if (updated > 0) {
            log.debug("Message acknowledged: clientId={}, messageId={}", clientId, messageId);
        }
    }

    @Transactional
    public void syncInbox(String clientId) {
        List<Inbox> undeliveredMessages = inboxRepository.findByClientIdAndDeliveredFalseOrderByTimestamp(clientId);

        if (undeliveredMessages.isEmpty()) {
            log.debug("No undelivered messages for clientId={}", clientId);
            return;
        }

        log.info("Syncing inbox: clientId={}, messageCount={}", clientId, undeliveredMessages.size());

        for (Inbox inboxEntry : undeliveredMessages) {
            Message message = messageRepository.findById(inboxEntry.getMessageId()).orElse(null);
            if (message != null) {
                deliverMessageToClient(clientId, message);
            }
        }
    }

    public void deliverMessageToClient(String clientId, Message message) {
        Client client = clientRepository.findById(clientId).orElse(null);
        if (client == null || !client.getOnline()) {
            log.debug("Client not online: clientId={}", clientId);
            return;
        }

        WebSocketSession session = connectionService.getSession(client.getUserId());
        if (session == null || !session.isOpen()) {
            log.debug("No active session for userId={}", client.getUserId());
            return;
        }

        try {
            WebSocketMessage wsMessage = WebSocketMessage.builder()
                .command(WebSocketMessage.MessageCommand.NEW_MESSAGE)
                .messageId(message.getId())
                .payload(Map.of(
                    "chatId", message.getChatId(),
                    "senderId", message.getSenderId(),
                    "message", message.getContent(),
                    "messageType", message.getType().toString(),
                    "attachments", message.getAttachmentUrls(),
                    "timestamp", message.getTimestamp().toString(),
                    "sequenceNumber", message.getSequenceNumber()
                ))
                .build();

            String payload = objectMapper.writeValueAsString(wsMessage);
            session.sendMessage(new TextMessage(payload));

            log.debug("Message delivered: clientId={}, messageId={}", clientId, message.getId());
        } catch (Exception e) {
            log.error("Error delivering message to client: clientId={}, messageId={}",
                     clientId, message.getId(), e);
        }
    }

    @Transactional(readOnly = true)
    public List<Message> getChatMessages(String chatId, int limit) {
        return messageRepository.findByChatIdOrderByTimestampDesc(chatId, PageRequest.of(0, limit));
    }

    @Transactional(readOnly = true)
    public List<Message> getChatMessagesSince(String chatId, LocalDateTime since) {
        return messageRepository.findByChatIdAndTimestampAfter(chatId, since);
    }

    private Long getNextSequenceNumber(String userId) {
        Long maxSeq = messageRepository.findMaxSequenceNumberByUserId(userId);
        return maxSeq != null ? maxSeq + 1 : 1L;
    }

    @Transactional
    public void cleanupExpiredMessages() {
        int deletedMessages = messageRepository.deleteExpiredMessages(LocalDateTime.now());
        int deletedInbox = inboxRepository.deleteExpiredInbox(LocalDateTime.now());
        log.info("Cleanup completed: deletedMessages={}, deletedInbox={}", deletedMessages, deletedInbox);
    }
}
