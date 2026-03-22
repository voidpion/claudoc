package com.claudoc.llm;

import com.claudoc.config.LlmConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.*;

@Component
@ConditionalOnProperty(name = "llm.provider", havingValue = "openai", matchIfMissing = true)
@Slf4j
public class OpenAiCompatibleClient implements LlmClient {

    private final WebClient webClient;
    private final LlmConfig config;
    private final ObjectMapper objectMapper;

    public OpenAiCompatibleClient(LlmConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.webClient = WebClient.builder()
                .baseUrl(config.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + config.getApiKey())
                .defaultHeader("Content-Type", "application/json")
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();
    }

    @Override
    public Flux<LlmResponseChunk> chatStream(List<ChatMessage> messages, List<ToolDefinition> tools) {
        Sinks.Many<LlmResponseChunk> sink = Sinks.many().unicast().onBackpressureBuffer();

        try {
            ObjectNode requestBody = buildRequestBody(messages, tools);
            log.debug("LLM request: {}", requestBody.toString());

            StreamState state = new StreamState();
            webClient.post()
                    .uri("/chat/completions")
                    .bodyValue(requestBody.toString())
                    .retrieve()
                    .bodyToFlux(String.class)
                    .doOnNext(line -> processSSELine(line, sink, state))
                    .doOnComplete(() -> sink.tryEmitComplete())
                    .doOnError(e -> {
                        log.error("LLM stream error", e);
                        sink.tryEmitError(e);
                    })
                    .subscribe();
        } catch (Exception e) {
            log.error("Failed to create LLM request", e);
            sink.tryEmitError(e);
        }

        return sink.asFlux();
    }

    private ObjectNode buildRequestBody(List<ChatMessage> messages, List<ToolDefinition> tools) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", config.getModel());
        body.put("max_tokens", config.getMaxTokens());
        body.put("temperature", config.getTemperature());
        body.put("stream", true);

        ArrayNode messagesArray = body.putArray("messages");
        for (ChatMessage msg : messages) {
            ObjectNode msgNode = objectMapper.createObjectNode();
            msgNode.put("role", msg.getRole());

            if (msg.getContent() != null) {
                msgNode.put("content", msg.getContent());
            }
            if (msg.getToolCallId() != null) {
                msgNode.put("tool_call_id", msg.getToolCallId());
            }
            if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
                ArrayNode toolCallsNode = msgNode.putArray("tool_calls");
                for (ToolCall tc : msg.getToolCalls()) {
                    ObjectNode tcNode = objectMapper.createObjectNode();
                    tcNode.put("id", tc.getId());
                    tcNode.put("type", "function");
                    ObjectNode fnNode = tcNode.putObject("function");
                    fnNode.put("name", tc.getFunction().getName());
                    fnNode.put("arguments", tc.getFunction().getArguments());
                    toolCallsNode.add(tcNode);
                }
            }
            messagesArray.add(msgNode);
        }

        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolsArray = body.putArray("tools");
            for (ToolDefinition tool : tools) {
                ObjectNode toolNode = objectMapper.valueToTree(tool);
                toolsArray.add(toolNode);
            }
        }

        return body;
    }

    /**
     * Per-request SSE parsing state. Each chatStream call creates its own instance,
     * avoiding shared mutable state on the singleton bean.
     */
    private static class StreamState {
        final Map<Integer, ToolCall> pendingToolCalls = new HashMap<>();
    }

    private void processSSELine(String rawLine, Sinks.Many<LlmResponseChunk> sink, StreamState state) {
        for (String line : rawLine.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.equals("data: [DONE]")) {
                if (line.equals("data: [DONE]")) {
                    // Emit any pending tool calls
                    for (ToolCall tc : state.pendingToolCalls.values()) {
                        sink.tryEmitNext(LlmResponseChunk.toolCall(tc));
                    }
                    state.pendingToolCalls.clear();
                    sink.tryEmitNext(LlmResponseChunk.done());
                }
                return;
            }

            if (!line.startsWith("data: ")) return;
            String json = line.substring(6);

            try {
                JsonNode node = objectMapper.readTree(json);
                JsonNode choices = node.get("choices");
                if (choices == null || choices.isEmpty()) return;

                JsonNode delta = choices.get(0).get("delta");
                if (delta == null) return;

                // Content delta
                if (delta.has("content") && !delta.get("content").isNull()) {
                    String content = delta.get("content").asText();
                    if (!content.isEmpty()) {
                        sink.tryEmitNext(LlmResponseChunk.content(content));
                    }
                }

                // Tool call delta
                if (delta.has("tool_calls")) {
                    for (JsonNode tcDelta : delta.get("tool_calls")) {
                        int index = tcDelta.get("index").asInt();
                        ToolCall existing = state.pendingToolCalls.get(index);

                        if (existing == null) {
                            existing = ToolCall.builder()
                                    .id(tcDelta.has("id") ? tcDelta.get("id").asText() : "")
                                    .type("function")
                                    .function(ToolCall.FunctionCall.builder()
                                            .name("")
                                            .arguments("")
                                            .build())
                                    .build();
                            state.pendingToolCalls.put(index, existing);
                        }

                        if (tcDelta.has("id")) {
                            existing.setId(tcDelta.get("id").asText());
                        }
                        if (tcDelta.has("function")) {
                            JsonNode fn = tcDelta.get("function");
                            if (fn.has("name") && !fn.get("name").isNull()) {
                                existing.getFunction().setName(
                                        existing.getFunction().getName() + fn.get("name").asText());
                            }
                            if (fn.has("arguments") && !fn.get("arguments").isNull()) {
                                existing.getFunction().setArguments(
                                        existing.getFunction().getArguments() + fn.get("arguments").asText());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to parse SSE line: {}", json, e);
            }
        }
    }
}
