package com.claudoc.llm;

import reactor.core.publisher.Flux;

import java.util.List;

public interface LlmClient {

    /**
     * Stream a chat completion with tool support.
     * Returns a flux of LlmResponseChunk.
     */
    Flux<LlmResponseChunk> chatStream(List<ChatMessage> messages, List<ToolDefinition> tools);
}
