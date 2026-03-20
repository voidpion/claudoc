package com.claudoc.llm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ToolDefinition {

    private String type;
    private FunctionDef function;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class FunctionDef {
        private String name;
        private String description;
        private Map<String, Object> parameters;
    }

    public static ToolDefinition of(String name, String description, Map<String, Object> parameters) {
        return ToolDefinition.builder()
                .type("function")
                .function(FunctionDef.builder()
                        .name(name)
                        .description(description)
                        .parameters(parameters)
                        .build())
                .build();
    }
}
