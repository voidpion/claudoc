package com.claudoc.llm;

import com.claudoc.config.LlmConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
@Primary
@ConditionalOnProperty(name = "llm.provider", havingValue = "anthropic")
@Slf4j
public class AnthropicClient implements LlmClient {

    private final HttpClient httpClient;
    private final LlmConfig config;
    private final ObjectMapper objectMapper;

    public AnthropicClient(LlmConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newHttpClient();
    }

    @Override
    public Flux<LlmResponseChunk> chatStream(List<ChatMessage> messages, List<ToolDefinition> tools) {
        Sinks.Many<LlmResponseChunk> sink = Sinks.many().unicast().onBackpressureBuffer();

        try {
            ObjectNode requestBody = buildRequestBody(messages, tools);
            String jsonBody = objectMapper.writeValueAsString(requestBody);
            log.info("Anthropic request: {}", jsonBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.getBaseUrl() + "/v1/messages"))
                    .header("x-api-key", config.getApiKey())
                    .header("anthropic-version", config.getAnthropicVersion())
                    .header("Content-Type", "application/json")
                    .header("User-Agent", config.getUserAgent())
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .build();

            // Send async and process SSE stream in background
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                    .thenAccept(response -> {
                        if (response.statusCode() >= 400) {
                            try {
                                String body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                                log.error("Anthropic API error {}: {}", response.statusCode(), body);
                                sink.tryEmitError(new RuntimeException(
                                        "Anthropic API error " + response.statusCode() + ": " + body));
                            } catch (Exception e) {
                                sink.tryEmitError(e);
                            }
                            return;
                        }

                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                processSSELine(line, sink);
                            }
                            sink.tryEmitComplete();
                        } catch (Exception e) {
                            log.error("Anthropic stream read error", e);
                            sink.tryEmitError(e);
                        }
                    })
                    .exceptionally(e -> {
                        log.error("Anthropic request failed", e);
                        sink.tryEmitError(e);
                        return null;
                    });
        } catch (Exception e) {
            log.error("Failed to create Anthropic request", e);
            sink.tryEmitError(e);
        }

        return sink.asFlux();
    }

    private ObjectNode buildRequestBody(List<ChatMessage> messages, List<ToolDefinition> tools) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", config.getModel());
        body.put("max_tokens", config.getMaxTokens());
        body.put("stream", true);

        // Extract system message
        String systemContent = null;
        List<ChatMessage> nonSystemMessages = new ArrayList<>();
        for (ChatMessage msg : messages) {
            if ("system".equals(msg.getRole())) {
                if (systemContent == null) {
                    systemContent = msg.getContent();
                } else {
                    systemContent += "\n\n" + msg.getContent();
                }
            } else {
                nonSystemMessages.add(msg);
            }
        }

        if (systemContent != null) {
            ArrayNode systemArray = body.putArray("system");
            ObjectNode textBlock = objectMapper.createObjectNode();
            textBlock.put("type", "text");
            textBlock.put("text", systemContent);
            systemArray.add(textBlock);
        }

        // Build messages array (Anthropic format)
        ArrayNode messagesArray = body.putArray("messages");
        // Anthropic requires alternating user/assistant. Merge consecutive same-role messages.
        String lastRole = null;
        ObjectNode currentMsg = null;

        for (ChatMessage msg : nonSystemMessages) {
            String role = mapRole(msg);

            if ("tool".equals(msg.getRole())) {
                // Tool results in Anthropic are user messages with tool_result content blocks
                if (currentMsg == null || !"user".equals(lastRole)) {
                    currentMsg = objectMapper.createObjectNode();
                    currentMsg.put("role", "user");
                    currentMsg.putArray("content");
                    messagesArray.add(currentMsg);
                    lastRole = "user";
                }
                ArrayNode content = (ArrayNode) currentMsg.get("content");
                ObjectNode toolResult = objectMapper.createObjectNode();
                toolResult.put("type", "tool_result");
                toolResult.put("tool_use_id", msg.getToolCallId());
                toolResult.put("content", msg.getContent());
                content.add(toolResult);
            } else if ("assistant".equals(role)) {
                currentMsg = objectMapper.createObjectNode();
                currentMsg.put("role", "assistant");

                if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
                    ArrayNode content = currentMsg.putArray("content");
                    if (msg.getContent() != null && !msg.getContent().isEmpty()) {
                        ObjectNode textBlock = objectMapper.createObjectNode();
                        textBlock.put("type", "text");
                        textBlock.put("text", msg.getContent());
                        content.add(textBlock);
                    }
                    for (ToolCall tc : msg.getToolCalls()) {
                        ObjectNode toolUse = objectMapper.createObjectNode();
                        toolUse.put("type", "tool_use");
                        toolUse.put("id", tc.getId());
                        toolUse.put("name", tc.getFunction().getName());
                        try {
                            toolUse.set("input", objectMapper.readTree(tc.getFunction().getArguments()));
                        } catch (Exception e) {
                            toolUse.putObject("input");
                        }
                        content.add(toolUse);
                    }
                } else {
                    currentMsg.put("content", msg.getContent() != null ? msg.getContent() : "");
                }

                messagesArray.add(currentMsg);
                lastRole = "assistant";
            } else {
                // user
                if (currentMsg != null && "user".equals(lastRole)) {
                    // Merge into existing user message
                    JsonNode existingContent = currentMsg.get("content");
                    if (existingContent.isTextual()) {
                        ArrayNode content = currentMsg.putArray("content");
                        ObjectNode textBlock = objectMapper.createObjectNode();
                        textBlock.put("type", "text");
                        textBlock.put("text", existingContent.asText());
                        content.add(textBlock);
                        ObjectNode newBlock = objectMapper.createObjectNode();
                        newBlock.put("type", "text");
                        newBlock.put("text", msg.getContent());
                        content.add(newBlock);
                    } else if (existingContent.isArray()) {
                        ObjectNode textBlock = objectMapper.createObjectNode();
                        textBlock.put("type", "text");
                        textBlock.put("text", msg.getContent());
                        ((ArrayNode) existingContent).add(textBlock);
                    }
                } else {
                    currentMsg = objectMapper.createObjectNode();
                    currentMsg.put("role", "user");
                    currentMsg.put("content", msg.getContent());
                    messagesArray.add(currentMsg);
                    lastRole = "user";
                }
            }
        }

        // Tools (Anthropic format)
        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolsArray = body.putArray("tools");
            for (ToolDefinition tool : tools) {
                ObjectNode toolNode = objectMapper.createObjectNode();
                toolNode.put("name", tool.getFunction().getName());
                toolNode.put("description", tool.getFunction().getDescription());
                toolNode.set("input_schema", objectMapper.valueToTree(tool.getFunction().getParameters()));
                toolsArray.add(toolNode);
            }
        }

        return body;
    }

    private String mapRole(ChatMessage msg) {
        return switch (msg.getRole()) {
            case "user" -> "user";
            case "assistant" -> "assistant";
            case "tool" -> "user"; // tool results are sent as user in Anthropic
            default -> "user";
        };
    }

    // State for accumulating streaming tool use blocks
    private final Map<Integer, ToolCall> pendingToolCalls = Collections.synchronizedMap(new HashMap<>());
    private int currentBlockIndex = -1;
    private String currentBlockType = null;

    private void processSSELine(String line, Sinks.Many<LlmResponseChunk> sink) {
        line = line.trim();
        if (line.isEmpty()) return;

        // Parse event type
        if (line.startsWith("event:")) {
            String event = line.substring(6).trim();
            if ("message_stop".equals(event)) {
                // Emit pending tool calls
                for (ToolCall tc : pendingToolCalls.values()) {
                    sink.tryEmitNext(LlmResponseChunk.toolCall(tc));
                }
                pendingToolCalls.clear();
                currentBlockIndex = -1;
                sink.tryEmitNext(LlmResponseChunk.done());
            }
            return;
        }

        if (!line.startsWith("data:")) return;
        String json = line.substring(5).trim();
        if (json.isEmpty()) return;

        try {
            JsonNode node = objectMapper.readTree(json);
            String type = node.has("type") ? node.get("type").asText() : "";

            switch (type) {
                case "content_block_start" -> {
                    currentBlockIndex = node.get("index").asInt();
                    JsonNode contentBlock = node.get("content_block");
                    currentBlockType = contentBlock.get("type").asText();

                    if ("tool_use".equals(currentBlockType)) {
                        ToolCall tc = ToolCall.builder()
                                .id(contentBlock.get("id").asText())
                                .type("function")
                                .function(ToolCall.FunctionCall.builder()
                                        .name(contentBlock.get("name").asText())
                                        .arguments("")
                                        .build())
                                .build();
                        pendingToolCalls.put(currentBlockIndex, tc);
                    }
                }
                case "content_block_delta" -> {
                    JsonNode delta = node.get("delta");
                    String deltaType = delta.get("type").asText();

                    if ("text_delta".equals(deltaType)) {
                        String text = delta.get("text").asText();
                        if (!text.isEmpty()) {
                            sink.tryEmitNext(LlmResponseChunk.content(text));
                        }
                    } else if ("input_json_delta".equals(deltaType)) {
                        String partialJson = delta.get("partial_json").asText();
                        ToolCall tc = pendingToolCalls.get(currentBlockIndex);
                        if (tc != null) {
                            tc.getFunction().setArguments(
                                    tc.getFunction().getArguments() + partialJson);
                        }
                    }
                }
                case "content_block_stop" -> {
                    currentBlockType = null;
                }
                // message_start, message_delta, ping — ignore
            }
        } catch (Exception e) {
            log.warn("Failed to parse Anthropic SSE: {}", json, e);
        }
    }
}
