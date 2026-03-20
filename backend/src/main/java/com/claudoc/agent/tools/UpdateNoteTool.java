package com.claudoc.agent.tools;

import com.claudoc.agent.AgentTool;
import com.claudoc.model.Document;
import com.claudoc.service.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class UpdateNoteTool implements AgentTool {

    private final KnowledgeBaseService knowledgeBaseService;

    @Override
    public String name() { return "update_note"; }

    @Override
    public String description() {
        return "Update an existing note's title and/or content.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "required", new String[]{"id"},
                "properties", Map.of(
                        "id", Map.of("type", "string", "description", "Document ID"),
                        "title", Map.of("type", "string", "description", "New title (optional)"),
                        "content", Map.of("type", "string", "description", "New content (optional)")
                )
        );
    }

    @Override
    public String execute(Map<String, Object> args) {
        try {
            String id = (String) args.get("id");
            String title = (String) args.getOrDefault("title", null);
            String content = (String) args.getOrDefault("content", null);
            Document doc = knowledgeBaseService.updateNote(id, title, content);
            return "Updated note '" + doc.getTitle() + "' at " + doc.getPath();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
