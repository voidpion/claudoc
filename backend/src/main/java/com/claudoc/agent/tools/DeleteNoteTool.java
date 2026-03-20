package com.claudoc.agent.tools;

import com.claudoc.agent.AgentTool;
import com.claudoc.service.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class DeleteNoteTool implements AgentTool {

    private final KnowledgeBaseService knowledgeBaseService;

    @Override
    public String name() { return "delete_note"; }

    @Override
    public String description() {
        return "Delete a note from the knowledge base by ID.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "required", new String[]{"id"},
                "properties", Map.of(
                        "id", Map.of("type", "string", "description", "Document ID to delete")
                )
        );
    }

    @Override
    public String execute(Map<String, Object> args) {
        try {
            knowledgeBaseService.deleteNote((String) args.get("id"));
            return "Note deleted successfully.";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
