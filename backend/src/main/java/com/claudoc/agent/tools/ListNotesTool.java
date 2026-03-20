package com.claudoc.agent.tools;

import com.claudoc.agent.AgentTool;
import com.claudoc.service.KnowledgeBaseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class ListNotesTool implements AgentTool {

    private final KnowledgeBaseService knowledgeBaseService;
    private final ObjectMapper objectMapper;

    @Override
    public String name() { return "list_notes"; }

    @Override
    public String description() {
        return "List all notes in the knowledge base as a directory tree structure.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of("type", "object", "properties", Map.of());
    }

    @Override
    public String execute(Map<String, Object> args) {
        try {
            var tree = knowledgeBaseService.listTree();
            return objectMapper.writeValueAsString(tree);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
