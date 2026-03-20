package com.claudoc.agent.memory;

import com.claudoc.config.AgentConfig;
import com.claudoc.llm.ChatMessage;
import com.claudoc.llm.LlmClient;
import com.claudoc.llm.LlmResponseChunk;
import com.claudoc.llm.ToolCall;
import com.claudoc.model.Message;
import com.claudoc.repository.MessageRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class MemoryManager {

    private static final String SYSTEM_PROMPT = """
            You are Claudoc, an AI assistant integrated with a knowledge base system.
            You can read, create, update, delete, search, and semantically retrieve notes.

            When the user shares important information, preferences, or decisions,
            proactively save them as memory notes under the '/_memory/' path using the create_note tool.

            When answering questions, use the 'retrieve' tool to find relevant information from the knowledge base.
            Use the 'search' tool for keyword-based lookups.

            Always be helpful, concise, and accurate. When you retrieve information from the knowledge base,
            cite the source document.
            """;

    private static final String COMPRESS_PROMPT = """
            Summarize the following conversation concisely, preserving all key facts, decisions,
            user preferences, and important context. Be specific - include names, numbers, and technical details.
            Output only the summary, no preamble.
            """;

    private final MessageRepository messageRepository;
    private final AgentConfig agentConfig;
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    public List<ChatMessage> buildContext(String conversationId) {
        List<ChatMessage> context = new ArrayList<>();
        context.add(ChatMessage.system(SYSTEM_PROMPT));

        // L2: global summary
        List<Message> l2Messages = messageRepository
                .findByConversationIdAndArchivedFalseAndLevelOrderByCreatedAtAsc(conversationId, 2);
        if (!l2Messages.isEmpty()) {
            Message l2 = l2Messages.get(l2Messages.size() - 1);
            context.add(ChatMessage.system("[Global Context Summary]\n" + l2.getContent()));
        }

        // L1: recent summaries
        List<Message> l1Messages = messageRepository
                .findByConversationIdAndArchivedFalseAndLevelOrderByCreatedAtAsc(conversationId, 1);
        for (Message l1 : l1Messages) {
            context.add(ChatMessage.system("[Recent Summary]\n" + l1.getContent()));
        }

        // L0: raw messages
        List<Message> l0Messages = messageRepository
                .findByConversationIdAndArchivedFalseAndLevelOrderByCreatedAtAsc(conversationId, 0);
        for (Message msg : l0Messages) {
            context.add(toChatMessage(msg));
        }

        return context;
    }

    public void compressIfNeeded(String conversationId) {
        // Check L0
        List<Message> l0Messages = messageRepository
                .findByConversationIdAndArchivedFalseAndLevelOrderByCreatedAtAsc(conversationId, 0);

        int estimatedTokens = estimateTokens(l0Messages);
        if (estimatedTokens > agentConfig.getMemory().getL0MaxTokens()) {
            log.info("L0 tokens ({}) exceeded threshold, compressing...", estimatedTokens);
            compressL0ToL1(conversationId, l0Messages);
        }

        // Check L1
        long l1Count = messageRepository.countByConversationIdAndArchivedFalseAndLevel(conversationId, 1);
        if (l1Count > agentConfig.getMemory().getL1MaxCount()) {
            log.info("L1 count ({}) exceeded threshold, compressing to L2...", l1Count);
            compressL1ToL2(conversationId);
        }
    }

    private void compressL0ToL1(String conversationId, List<Message> l0Messages) {
        // Take the older half of L0 messages
        int splitPoint = l0Messages.size() / 2;
        List<Message> toCompress = l0Messages.subList(0, splitPoint);

        if (toCompress.isEmpty()) return;

        // Build summary request
        StringBuilder conversationText = new StringBuilder();
        for (Message msg : toCompress) {
            conversationText.append(msg.getRole()).append(": ").append(msg.getContent()).append("\n\n");
        }

        String summary = callLlmForSummary(conversationText.toString());

        // Save summary as L1 message
        Message l1 = Message.builder()
                .id(UUID.randomUUID().toString())
                .conversationId(conversationId)
                .role("system")
                .content(summary)
                .level(1)
                .build();
        messageRepository.save(l1);

        // Archive old L0 messages
        for (Message msg : toCompress) {
            msg.setArchived(true);
            messageRepository.save(msg);
        }

        log.info("Compressed {} L0 messages into L1 summary", toCompress.size());
    }

    private void compressL1ToL2(String conversationId) {
        List<Message> l1Messages = messageRepository
                .findByConversationIdAndArchivedFalseAndLevelOrderByCreatedAtAsc(conversationId, 1);

        // Also include existing L2 if any
        List<Message> l2Messages = messageRepository
                .findByConversationIdAndArchivedFalseAndLevelOrderByCreatedAtAsc(conversationId, 2);

        StringBuilder allSummaries = new StringBuilder();
        for (Message l2 : l2Messages) {
            allSummaries.append("[Previous Global Summary]\n").append(l2.getContent()).append("\n\n");
        }
        for (Message l1 : l1Messages) {
            allSummaries.append("[Summary]\n").append(l1.getContent()).append("\n\n");
        }

        String globalSummary = callLlmForSummary(allSummaries.toString());

        // Save new L2
        Message newL2 = Message.builder()
                .id(UUID.randomUUID().toString())
                .conversationId(conversationId)
                .role("system")
                .content(globalSummary)
                .level(2)
                .build();
        messageRepository.save(newL2);

        // Archive old L1 and L2
        for (Message msg : l1Messages) {
            msg.setArchived(true);
            messageRepository.save(msg);
        }
        for (Message msg : l2Messages) {
            msg.setArchived(true);
            messageRepository.save(msg);
        }

        log.info("Compressed {} L1 + {} L2 messages into new L2 global summary",
                l1Messages.size(), l2Messages.size());
    }

    private String callLlmForSummary(String text) {
        List<ChatMessage> messages = List.of(
                ChatMessage.system(COMPRESS_PROMPT),
                ChatMessage.user(text)
        );

        StringBuilder summary = new StringBuilder();
        llmClient.chatStream(messages, null)
                .filter(chunk -> chunk.getType() == LlmResponseChunk.Type.CONTENT)
                .doOnNext(chunk -> summary.append(chunk.getContent()))
                .blockLast();

        return summary.toString();
    }

    public void saveMessage(String conversationId, String role, String content,
                            String toolCallId, String toolName) {
        saveMessage(conversationId, role, content, toolCallId, toolName, null);
    }

    public void saveMessage(String conversationId, String role, String content,
                            String toolCallId, String toolName, List<ToolCall> toolCalls) {
        String toolCallsJson = null;
        if (toolCalls != null && !toolCalls.isEmpty()) {
            try {
                toolCallsJson = objectMapper.writeValueAsString(toolCalls);
            } catch (Exception e) {
                log.warn("Failed to serialize tool calls", e);
            }
        }
        Message msg = Message.builder()
                .id(UUID.randomUUID().toString())
                .conversationId(conversationId)
                .role(role)
                .content(content)
                .toolCallId(toolCallId)
                .toolName(toolName)
                .toolCallsJson(toolCallsJson)
                .level(0)
                .build();
        messageRepository.save(msg);
    }

    private int estimateTokens(List<Message> messages) {
        return messages.stream()
                .mapToInt(m -> m.getContent() == null ? 0 : m.getContent().length() / 4)
                .sum();
    }

    private ChatMessage toChatMessage(Message msg) {
        ChatMessage cm = switch (msg.getRole()) {
            case "user" -> ChatMessage.user(msg.getContent());
            case "assistant" -> ChatMessage.assistant(msg.getContent());
            case "tool" -> ChatMessage.tool(msg.getToolCallId(), msg.getContent());
            case "system" -> ChatMessage.system(msg.getContent());
            default -> ChatMessage.user(msg.getContent());
        };
        // Restore tool calls for assistant messages
        if ("assistant".equals(msg.getRole()) && msg.getToolCallsJson() != null) {
            try {
                List<ToolCall> toolCalls = objectMapper.readValue(
                        msg.getToolCallsJson(), new TypeReference<List<ToolCall>>() {});
                cm.setToolCalls(toolCalls);
            } catch (Exception e) {
                log.warn("Failed to deserialize tool calls", e);
            }
        }
        return cm;
    }
}
