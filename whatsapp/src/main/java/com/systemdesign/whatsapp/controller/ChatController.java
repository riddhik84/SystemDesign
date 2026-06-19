package com.systemdesign.whatsapp.controller;

import com.systemdesign.whatsapp.model.Chat;
import com.systemdesign.whatsapp.model.Message;
import com.systemdesign.whatsapp.service.ChatService;
import com.systemdesign.whatsapp.service.MessageService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/chats")
@RequiredArgsConstructor
public class ChatController {
    private final ChatService chatService;
    private final MessageService messageService;

    @PostMapping
    public ResponseEntity<ChatResponse> createChat(@RequestBody CreateChatRequest request) {
        String chatId = chatService.createChat(
            request.getParticipants(),
            request.getName(),
            request.getCreatorId()
        );

        ChatResponse response = new ChatResponse();
        response.setChatId(chatId);
        response.setStatus("CREATED");

        return ResponseEntity.ok(response);
    }

    @GetMapping("/{chatId}")
    public ResponseEntity<Chat> getChat(@PathVariable String chatId) {
        Chat chat = chatService.getChat(chatId);
        return ResponseEntity.ok(chat);
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<List<Chat>> getUserChats(@PathVariable String userId) {
        List<Chat> chats = chatService.getUserChats(userId);
        return ResponseEntity.ok(chats);
    }

    @GetMapping("/{chatId}/messages")
    public ResponseEntity<List<Message>> getChatMessages(
            @PathVariable String chatId,
            @RequestParam(defaultValue = "50") int limit) {
        List<Message> messages = messageService.getChatMessages(chatId, limit);
        return ResponseEntity.ok(messages);
    }

    @PostMapping("/{chatId}/participants")
    public ResponseEntity<Void> addParticipant(
            @PathVariable String chatId,
            @RequestBody ModifyParticipantRequest request) {
        chatService.addParticipant(chatId, request.getUserId());
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{chatId}/participants/{userId}")
    public ResponseEntity<Void> removeParticipant(
            @PathVariable String chatId,
            @PathVariable String userId) {
        chatService.removeParticipant(chatId, userId);
        return ResponseEntity.ok().build();
    }

    @Data
    public static class CreateChatRequest {
        private List<String> participants;
        private String name;
        private String creatorId;
    }

    @Data
    public static class ModifyParticipantRequest {
        private String userId;
    }

    @Data
    public static class ChatResponse {
        private String chatId;
        private String status;
    }
}
