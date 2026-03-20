package com.claudoc.llm;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatMessage {

    private String role;  // system, user, assistant, tool
    private String content;
    private List<ToolCall> toolCalls;
    private String toolCallId;

    public static ChatMessage system(String content) {
        return ChatMessage.builder().role("system").content(content).build();
    }

    public static ChatMessage user(String content) {
        return ChatMessage.builder().role("user").content(content).build();
    }

    public static ChatMessage assistant(String content) {
        return ChatMessage.builder().role("assistant").content(content).build();
    }

    public static ChatMessage assistantWithToolCalls(List<ToolCall> toolCalls) {
        return ChatMessage.builder().role("assistant").toolCalls(toolCalls).build();
    }

    public static ChatMessage tool(String toolCallId, String content) {
        return ChatMessage.builder().role("tool").toolCallId(toolCallId).content(content).build();
    }
}
