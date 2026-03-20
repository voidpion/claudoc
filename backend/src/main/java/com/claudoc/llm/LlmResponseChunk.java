package com.claudoc.llm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LlmResponseChunk {

    public enum Type {
        CONTENT,       // text content delta
        TOOL_CALL,     // tool call (accumulated)
        DONE           // stream finished
    }

    private Type type;
    private String content;
    private ToolCall toolCall;

    public static LlmResponseChunk content(String text) {
        return LlmResponseChunk.builder().type(Type.CONTENT).content(text).build();
    }

    public static LlmResponseChunk toolCall(ToolCall toolCall) {
        return LlmResponseChunk.builder().type(Type.TOOL_CALL).toolCall(toolCall).build();
    }

    public static LlmResponseChunk done() {
        return LlmResponseChunk.builder().type(Type.DONE).build();
    }
}
