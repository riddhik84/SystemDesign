package com.systemdesign.whatsapp.service;

import com.systemdesign.whatsapp.model.Chat;
import com.systemdesign.whatsapp.model.ChatParticipant;
import com.systemdesign.whatsapp.repository.ChatParticipantRepository;
import com.systemdesign.whatsapp.repository.ChatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatService {
    private final ChatRepository chatRepository;
    private final ChatParticipantRepository participantRepository;
    private final RedisPubSubService redisPubSubService;

    @Transactional
    public String createChat(List<String> participantIds, String name, String creatorId) {
        if (participantIds == null || participantIds.isEmpty()) {
            throw new IllegalArgumentException("At least one participant required");
        }

        if (participantIds.size() > 100) {
            throw new IllegalArgumentException("Maximum 100 participants allowed");
        }

        Chat.ChatType chatType = participantIds.size() == 2 ? Chat.ChatType.DIRECT : Chat.ChatType.GROUP;

        if (chatType == Chat.ChatType.DIRECT) {
            List<Chat> existingChats = chatRepository.findDirectChatBetweenUsers(participantIds.get(0), participantIds.get(1));
            if (!existingChats.isEmpty()) {
                return existingChats.get(0).getId();
            }
        }

        Chat chat = Chat.builder()
            .name(name != null ? name : generateChatName(participantIds))
            .type(chatType)
            .creatorId(creatorId)
            .createdAt(LocalDateTime.now())
            .build();

        chat = chatRepository.save(chat);

        for (String participantId : participantIds) {
            ChatParticipant participant = ChatParticipant.builder()
                .chat(chat)
                .userId(participantId)
                .joinedAt(LocalDateTime.now())
                .active(true)
                .build();
            participantRepository.save(participant);
        }

        redisPubSubService.publishChatUpdate(chat.getId(), participantIds);

        log.info("Chat created: chatId={}, type={}, participants={}", chat.getId(), chatType, participantIds.size());
        return chat.getId();
    }

    @Transactional
    public void addParticipant(String chatId, String userId) {
        Chat chat = chatRepository.findById(chatId)
            .orElseThrow(() -> new RuntimeException("Chat not found"));

        if (chat.getType() == Chat.ChatType.DIRECT) {
            throw new RuntimeException("Cannot add participants to direct chat");
        }

        Long participantCount = participantRepository.countByChatIdAndActiveTrue(chatId);
        if (participantCount >= 100) {
            throw new RuntimeException("Maximum 100 participants allowed");
        }

        ChatParticipant existingParticipant = participantRepository.findByChatIdAndUserId(chatId, userId)
            .orElse(null);

        if (existingParticipant != null) {
            if (existingParticipant.getActive()) {
                throw new RuntimeException("User already in chat");
            }
            existingParticipant.setActive(true);
            existingParticipant.setJoinedAt(LocalDateTime.now());
            existingParticipant.setLeftAt(null);
            participantRepository.save(existingParticipant);
        } else {
            ChatParticipant participant = ChatParticipant.builder()
                .chat(chat)
                .userId(userId)
                .joinedAt(LocalDateTime.now())
                .active(true)
                .build();
            participantRepository.save(participant);
        }

        List<String> activeParticipants = participantRepository.findActiveUserIdsByChatId(chatId);
        redisPubSubService.publishChatUpdate(chatId, activeParticipants);

        log.info("Participant added: chatId={}, userId={}", chatId, userId);
    }

    @Transactional
    public void removeParticipant(String chatId, String userId) {
        Chat chat = chatRepository.findById(chatId)
            .orElseThrow(() -> new RuntimeException("Chat not found"));

        if (chat.getType() == Chat.ChatType.DIRECT) {
            throw new RuntimeException("Cannot remove participants from direct chat");
        }

        ChatParticipant participant = participantRepository.findByChatIdAndUserId(chatId, userId)
            .orElseThrow(() -> new RuntimeException("Participant not found"));

        participant.setActive(false);
        participant.setLeftAt(LocalDateTime.now());
        participantRepository.save(participant);

        List<String> activeParticipants = participantRepository.findActiveUserIdsByChatId(chatId);
        redisPubSubService.publishChatUpdate(chatId, activeParticipants);

        log.info("Participant removed: chatId={}, userId={}", chatId, userId);
    }

    @Transactional(readOnly = true)
    public List<Chat> getUserChats(String userId) {
        return chatRepository.findByUserId(userId);
    }

    @Transactional(readOnly = true)
    public Chat getChat(String chatId) {
        return chatRepository.findById(chatId)
            .orElseThrow(() -> new RuntimeException("Chat not found"));
    }

    @Transactional(readOnly = true)
    public List<String> getChatParticipants(String chatId) {
        return participantRepository.findActiveUserIdsByChatId(chatId);
    }

    private String generateChatName(List<String> participantIds) {
        return "Chat " + participantIds.size() + " users";
    }
}
