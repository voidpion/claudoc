package com.claudoc.agent.memory;

import com.claudoc.agent.UiActionTracker;
import com.claudoc.config.AgentConfig;
import com.claudoc.llm.ChatMessage;
import com.claudoc.llm.LlmClient;
import com.claudoc.llm.LlmResponseChunk;
import com.claudoc.llm.ToolCall;
import com.claudoc.model.Document;
import com.claudoc.model.Message;
import com.claudoc.repository.DocumentRepository;
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

    // ── Step 1: Structured System Prompt ──
    private static final String IDENTITY_AND_RULES = """
            You are Claudoc, an AI assistant integrated with a knowledge base system.
            You help users manage, search, and understand their notes. Be helpful, concise, and accurate.
            When you retrieve information from the knowledge base, always cite the source document (path or title).
            When the user shares important information, preferences, or decisions,
            proactively save them as memory notes under the '/_memory/' path using create_note.

            ## Path and Directory Rules
            - Directories do NOT exist independently. They are created implicitly by note paths.
              For example, creating a note at '/logs/2024-01-01.md' implicitly creates the '/logs/' directory.
            - When the user asks to "create a directory/folder", create an initial note under that path.
              e.g. "Create a logs directory" → create_note(path="/logs/README.md", title="Logs", content="...")
            - The '/_memory/' path is ONLY for the agent's own memory notes (user preferences, decisions).
              NEVER put user-requested content under '/_memory/'. Use appropriate top-level paths instead.
              e.g. user says "create a log directory" → use '/logs/', NOT '/_memory/logs/'
            """;

    // ── Step 3: Tool Chaining Workflows ──
    private static final String TOOL_GUIDANCE = """
            ## Tool Selection Guide

            Choose the right tool based on the user's intent:
            - "What notes are there?" / "Show files" → list_notes
            - Question about a topic → retrieve (semantic search) → optionally read_note for full text
            - "Find notes containing X" / exact term lookup → search (keyword match)
            - "Create a note about X" → first search to check duplicates → create_note
            - "Edit/update note X" → first read_note to see current content → update_note
            - "Delete note X" → delete_note (moves to trash, confirm with user first if ambiguous)
            - "Restore note" / "Undo delete" → list_trash to find ID → restore_note
            - "Show trash" / "Recycle bin" → list_trash

            ## Common Workflows

            1. Answering knowledge questions:
               retrieve(query) → read_note(doc_id) for full context → answer with citation
            2. Creating a new note:
               search(topic) to check existence → create_note(path, title, content)
            3. Updating a note:
               read_note(id) to get current content → update_note(id, new_content)
            4. Deleting and restoring:
               delete_note(id) moves to trash → list_trash to review → restore_note(id) to recover

            ## Important Rules
            - Prefer 'retrieve' over 'search' for open-ended questions (semantic > keyword).
            - After retrieve, if a chunk looks relevant but incomplete, use read_note to get the full document.
            - Never guess document IDs — always get them from tool results (list_notes, search, or retrieve).
            - delete_note is a soft delete (trash). Notes can be restored with restore_note.
            """;

    private static final String COMPRESS_PROMPT = """
            Summarize the following conversation concisely, preserving:
            - All key facts, decisions, and user preferences
            - Document IDs and paths that were referenced
            - Technical details (names, numbers, configurations)
            - What tools were called and their key results (not full output)
            Omit verbose tool output details. Output only the summary, no preamble.
            """;

    private static final int TOOL_RESULT_MAX_LENGTH = 500;

    private final MessageRepository messageRepository;
    private final DocumentRepository documentRepository;
    private final UiActionTracker uiActionTracker;
    private final AgentConfig agentConfig;
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    public List<ChatMessage> buildContext(String conversationId) {
        List<ChatMessage> context = new ArrayList<>();

        // [Identity & Rules]
        context.add(ChatMessage.system(IDENTITY_AND_RULES));

        // [Tool Selection Guide & Workflows]
        context.add(ChatMessage.system(TOOL_GUIDANCE));

        // [Dynamic KB Overview] — injected so simple questions don't need tool calls
        String kbOverview = buildKbOverview();
        if (!kbOverview.isEmpty()) {
            context.add(ChatMessage.system("[Knowledge Base Overview]\n" + kbOverview));
        }

        // [UI Context] — recent user actions from the web UI
        String uiContext = uiActionTracker.format();
        if (!uiContext.isEmpty()) {
            context.add(ChatMessage.system(uiContext));
        }

        // [L2: Global Summary]
        List<Message> l2Messages = messageRepository
                .findByConversationIdAndArchivedFalseAndLevelOrderByCreatedAtAsc(conversationId, 2);
        if (!l2Messages.isEmpty()) {
            Message l2 = l2Messages.get(l2Messages.size() - 1);
            context.add(ChatMessage.system("[Global Context Summary]\n" + l2.getContent()));
        }

        // [L1: Recent Summaries]
        List<Message> l1Messages = messageRepository
                .findByConversationIdAndArchivedFalseAndLevelOrderByCreatedAtAsc(conversationId, 1);
        for (Message l1 : l1Messages) {
            context.add(ChatMessage.system("[Recent Summary]\n" + l1.getContent()));
        }

        // [L0: Raw Messages]
        List<Message> l0Messages = messageRepository
                .findByConversationIdAndArchivedFalseAndLevelOrderByCreatedAtAsc(conversationId, 0);
        for (Message msg : l0Messages) {
            context.add(toChatMessage(msg));
        }

        return context;
    }

    /**
     * Build a compact overview of the knowledge base for injection into system prompt.
     * Lists document count, paths and titles — enough for the model to answer
     * "what's in the KB" without calling list_notes.
     */
    private String buildKbOverview() {
        try {
            List<Document> docs = documentRepository.findAll();
            if (docs.isEmpty()) return "The knowledge base is empty.";

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("%d documents:\n", docs.size()));
            for (Document doc : docs) {
                sb.append(String.format("- %s \"%s\" (id: %s)\n", doc.getPath(), doc.getTitle(), doc.getId()));
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("Failed to build KB overview", e);
            return "";
        }
    }

    // ── Step 4: Improved Compression ──

    public void compressIfNeeded(String conversationId) {
        List<Message> l0Messages = messageRepository
                .findByConversationIdAndArchivedFalseAndLevelOrderByCreatedAtAsc(conversationId, 0);

        int estimatedTokens = estimateTokens(l0Messages);
        if (estimatedTokens > agentConfig.getMemory().getL0MaxTokens()) {
            log.info("L0 tokens ({}) exceeded threshold, compressing...", estimatedTokens);
            compressL0ToL1(conversationId, l0Messages);
        }

        long l1Count = messageRepository.countByConversationIdAndArchivedFalseAndLevel(conversationId, 1);
        if (l1Count > agentConfig.getMemory().getL1MaxCount()) {
            log.info("L1 count ({}) exceeded threshold, compressing to L2...", l1Count);
            compressL1ToL2(conversationId);
        }
    }

    /**
     * Improved L0→L1: compress by complete conversation rounds, not arbitrary split.
     * A "round" is: user message + assistant response + any tool calls/results in between.
     * We keep the most recent rounds and compress the older ones.
     */
    private void compressL0ToL1(String conversationId, List<Message> l0Messages) {
        // Find split point at a user message boundary (keep recent complete rounds)
        int splitPoint = findRoundBoundary(l0Messages);
        if (splitPoint <= 0) return;

        List<Message> toCompress = new ArrayList<>(l0Messages.subList(0, splitPoint));
        if (toCompress.isEmpty()) return;

        // Build summary — truncate tool results for compression input
        StringBuilder conversationText = new StringBuilder();
        for (Message msg : toCompress) {
            String content = msg.getContent();
            if ("tool".equals(msg.getRole()) && content != null && content.length() > TOOL_RESULT_MAX_LENGTH) {
                content = content.substring(0, TOOL_RESULT_MAX_LENGTH) + "...[truncated]";
            }
            conversationText.append(msg.getRole()).append(": ")
                    .append(content != null ? content : "[tool call]").append("\n\n");
        }

        String summary = callLlmForSummary(conversationText.toString());

        Message l1 = Message.builder()
                .id(UUID.randomUUID().toString())
                .conversationId(conversationId)
                .role("system")
                .content(summary)
                .level(1)
                .build();
        messageRepository.save(l1);

        for (Message msg : toCompress) {
            msg.setArchived(true);
            messageRepository.save(msg);
        }

        log.info("Compressed {} L0 messages into L1 summary (split at round boundary)", toCompress.size());
    }

    /**
     * Find a split point that respects conversation round boundaries.
     * Scans from the middle backward to find a "user" message start,
     * ensuring we don't split in the middle of a tool call chain.
     */
    private int findRoundBoundary(List<Message> messages) {
        int target = messages.size() / 2;

        // Search backward from target to find a user message (start of a round)
        for (int i = target; i > 0; i--) {
            if ("user".equals(messages.get(i).getRole())) {
                return i; // Split just before this user message
            }
        }

        // Search forward if backward didn't find one
        for (int i = target + 1; i < messages.size() - 1; i++) {
            if ("user".equals(messages.get(i).getRole())) {
                return i;
            }
        }

        // Fallback to half
        return target;
    }

    private void compressL1ToL2(String conversationId) {
        List<Message> l1Messages = messageRepository
                .findByConversationIdAndArchivedFalseAndLevelOrderByCreatedAtAsc(conversationId, 1);
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

        Message newL2 = Message.builder()
                .id(UUID.randomUUID().toString())
                .conversationId(conversationId)
                .role("system")
                .content(globalSummary)
                .level(2)
                .build();
        messageRepository.save(newL2);

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
        if ("assistant".equals(msg.getRole()) && msg.getToolCallsJson() != null) {
            try {
                List<ToolCall> tcs = objectMapper.readValue(
                        msg.getToolCallsJson(), new TypeReference<List<ToolCall>>() {});
                cm.setToolCalls(tcs);
            } catch (Exception e) {
                log.warn("Failed to deserialize tool calls", e);
            }
        }
        return cm;
    }
}
