package com.claudoc.agent;

import com.claudoc.agent.memory.MemoryManager;
import com.claudoc.llm.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
@RequiredArgsConstructor
@Slf4j
public class AgentLoop {

    private static final int MAX_TOOL_ITERATIONS = 10;

    /** Tools that modify the knowledge base and require UI refresh. */
    private static final Map<String, List<String>> TOOL_REFRESH_MAP = Map.of(
            "create_note",   List.of("tree"),
            "update_note",   List.of("tree", "document"),
            "delete_note",   List.of("tree", "trash"),
            "restore_note",  List.of("tree", "trash"),
            "list_trash",    List.of("trash")
    );

    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final MemoryManager memoryManager;
    private final ObjectMapper objectMapper;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    public void run(String conversationId, String userMessage, SseEmitter emitter) {
        executor.submit(() -> {
            try {
                executeLoop(conversationId, userMessage, emitter);
            } catch (Exception e) {
                log.error("Agent loop error", e);
                try {
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data("{\"error\":\"" + e.getMessage() + "\"}"));
                    emitter.complete();
                } catch (Exception ex) {
                    log.error("Failed to send error to client", ex);
                }
            }
        });
    }

    private void executeLoop(String conversationId, String userMessage, SseEmitter emitter)
            throws Exception {

        // Save user message
        memoryManager.saveMessage(conversationId, "user", userMessage, null, null);

        int iteration = 0;
        while (iteration < MAX_TOOL_ITERATIONS) {
            iteration++;

            // Build context
            List<ChatMessage> context = memoryManager.buildContext(conversationId);
            List<ToolDefinition> tools = toolRegistry.getAllDefinitions();

            // Call LLM
            StringBuilder contentBuffer = new StringBuilder();
            List<ToolCall> toolCalls = new ArrayList<>();

            llmClient.chatStream(context, tools)
                    .doOnNext(chunk -> {
                        try {
                            switch (chunk.getType()) {
                                case CONTENT -> {
                                    contentBuffer.append(chunk.getContent());
                                    emitter.send(SseEmitter.event()
                                            .name("content")
                                            .data(objectMapper.writeValueAsString(
                                                    Map.of("text", chunk.getContent()))));
                                }
                                case TOOL_CALL -> {
                                    toolCalls.add(chunk.getToolCall());
                                    emitter.send(SseEmitter.event()
                                            .name("tool_call")
                                            .data(objectMapper.writeValueAsString(Map.of(
                                                    "id", chunk.getToolCall().getId(),
                                                    "name", chunk.getToolCall().getFunction().getName(),
                                                    "arguments", chunk.getToolCall().getFunction().getArguments()
                                            ))));
                                }
                                case DONE -> {}
                            }
                        } catch (Exception e) {
                            log.error("Error sending SSE event", e);
                        }
                    })
                    .blockLast();

            // If there's text content and no tool calls, we're done
            if (!contentBuffer.isEmpty() && toolCalls.isEmpty()) {
                memoryManager.saveMessage(conversationId, "assistant", contentBuffer.toString(), null, null);
                break;
            }

            // If there are tool calls, execute them
            if (!toolCalls.isEmpty()) {
                // Save assistant message with tool calls
                memoryManager.saveMessage(conversationId, "assistant",
                        contentBuffer.isEmpty() ? null : contentBuffer.toString(),
                        null, null, toolCalls);

                for (ToolCall tc : toolCalls) {
                    String toolName = tc.getFunction().getName();
                    String argsJson = tc.getFunction().getArguments();

                    log.info("Executing tool: {} with args: {}", toolName, argsJson);

                    Map<String, Object> args;
                    try {
                        args = objectMapper.readValue(argsJson, new TypeReference<>() {});
                    } catch (Exception e) {
                        args = Map.of();
                    }

                    String result = toolRegistry.executeTool(toolName, args);

                    // Send tool result to client
                    emitter.send(SseEmitter.event()
                            .name("tool_result")
                            .data(objectMapper.writeValueAsString(Map.of(
                                    "tool_call_id", tc.getId(),
                                    "name", toolName,
                                    "result", result
                            ))));

                    // Send ui_sync event if the tool modifies data
                    List<String> refreshTargets = TOOL_REFRESH_MAP.get(toolName);
                    if (refreshTargets != null) {
                        Map<String, Object> syncData = new HashMap<>();
                        syncData.put("refresh", refreshTargets);
                        // For update_note, include documentId so frontend can refresh if viewing it
                        if (args.containsKey("id")) {
                            syncData.put("documentId", args.get("id"));
                        }
                        emitter.send(SseEmitter.event()
                                .name("ui_sync")
                                .data(objectMapper.writeValueAsString(syncData)));
                    }

                    // Save tool result as message
                    memoryManager.saveMessage(conversationId, "tool", result, tc.getId(), toolName);
                }

                // Continue loop to get LLM's response after tool results
                continue;
            }

            // No content and no tool calls - shouldn't happen, but break
            break;
        }

        if (iteration >= MAX_TOOL_ITERATIONS) {
            emitter.send(SseEmitter.event()
                    .name("content")
                    .data("\n\n[Reached maximum tool iterations]"));
        }

        // Send done event
        emitter.send(SseEmitter.event().name("done").data("{}"));
        emitter.complete();

        // Trigger memory compression asynchronously
        executor.submit(() -> {
            try {
                memoryManager.compressIfNeeded(conversationId);
            } catch (Exception e) {
                log.error("Memory compression error", e);
            }
        });
    }
}
