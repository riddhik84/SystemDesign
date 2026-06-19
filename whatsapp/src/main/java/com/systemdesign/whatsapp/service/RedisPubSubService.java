package com.systemdesign.whatsapp.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.systemdesign.whatsapp.model.Message;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class RedisPubSubService {
    private final RedisTemplate<String, String> redisTemplate;
    private final RedisMessageListenerContainer messageListenerContainer;
    private final ObjectMapper objectMapper;

    public void publishMessage(Message message, List<String> recipientUserIds) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("messageId", message.getId());
            payload.put("chatId", message.getChatId());
            payload.put("senderId", message.getSenderId());
            payload.put("content", message.getContent());
            payload.put("type", message.getType().toString());
            payload.put("timestamp", message.getTimestamp().toString());
            payload.put("attachments", message.getAttachmentUrls());
            payload.put("sequenceNumber", message.getSequenceNumber());

            String jsonPayload = objectMapper.writeValueAsString(payload);

            for (String userId : recipientUserIds) {
                String channel = "user:" + userId;
                redisTemplate.convertAndSend(channel, jsonPayload);
                log.debug("Published message to Redis: channel={}, messageId={}", channel, message.getId());
            }
        } catch (JsonProcessingException e) {
            log.error("Error publishing message to Redis", e);
        }
    }

    public void publishChatUpdate(String chatId, List<String> participantUserIds) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("chatId", chatId);
            payload.put("participants", participantUserIds);
            payload.put("type", "CHAT_UPDATE");

            String jsonPayload = objectMapper.writeValueAsString(payload);

            for (String userId : participantUserIds) {
                String channel = "user:" + userId;
                redisTemplate.convertAndSend(channel, jsonPayload);
                log.debug("Published chat update to Redis: channel={}, chatId={}", channel, chatId);
            }
        } catch (JsonProcessingException e) {
            log.error("Error publishing chat update to Redis", e);
        }
    }

    public void subscribeToUserChannel(String userId) {
        String channel = "user:" + userId;
        messageListenerContainer.addMessageListener(
            (message, pattern) -> handleRedisMessage(userId, message),
            new ChannelTopic(channel)
        );
        log.info("Subscribed to Redis channel: {}", channel);
    }

    private void handleRedisMessage(String userId, org.springframework.data.redis.connection.Message message) {
        try {
            String payload = new String(message.getBody());
            log.debug("Received Redis message: userId={}, payload={}", userId, payload);
        } catch (Exception e) {
            log.error("Error handling Redis message", e);
        }
    }
}
