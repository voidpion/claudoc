package com.claudoc.controller;

import com.claudoc.agent.AgentLoop;
import com.claudoc.model.Conversation;
import com.claudoc.repository.ConversationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ChatController {

    private final AgentLoop agentLoop;
    private final ConversationRepository conversationRepository;

    @PostMapping("/conversations")
    public Conversation createConversation(@RequestBody(required = false) Map<String, String> body) {
        Conversation conv = Conversation.builder()
                .id(UUID.randomUUID().toString())
                .title(body != null ? body.getOrDefault("title", "New Chat") : "New Chat")
                .build();
        return conversationRepository.save(conv);
    }

    @GetMapping("/conversations")
    public List<Conversation> listConversations() {
        return conversationRepository.findAll();
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody Map<String, String> body) {
        String conversationId = body.get("conversationId");
        String message = body.get("message");

        // Auto-create conversation if not provided or not found (H2 is in-memory, data lost on restart)
        if (conversationId == null || conversationId.isBlank()
                || conversationRepository.findById(conversationId).isEmpty()) {
            Conversation conv = Conversation.builder()
                    .id(conversationId != null && !conversationId.isBlank()
                            ? conversationId : UUID.randomUUID().toString())
                    .title(message.length() > 50 ? message.substring(0, 50) + "..." : message)
                    .build();
            conversationRepository.save(conv);
            conversationId = conv.getId();
        }

        SseEmitter emitter = new SseEmitter(300_000L); // 5 min timeout

        agentLoop.run(conversationId, message, emitter);

        return emitter;
    }
}
