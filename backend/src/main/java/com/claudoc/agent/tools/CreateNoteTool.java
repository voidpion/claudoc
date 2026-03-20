package com.claudoc.agent.tools;

import com.claudoc.agent.AgentTool;
import com.claudoc.model.Document;
import com.claudoc.service.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class CreateNoteTool implements AgentTool {

    private final KnowledgeBaseService knowledgeBaseService;

    @Override
    public String name() { return "create_note"; }

    @Override
    public String description() {
        return "Create a new note in the knowledge base. Use path prefix '/_memory/' for agent memory notes.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "required", new String[]{"path", "title", "content"},
                "properties", Map.of(
                        "path", Map.of("type", "string", "description", "File path, e.g. '/notes/my-note.md'"),
                        "title", Map.of("type", "string", "description", "Note title"),
                        "content", Map.of("type", "string", "description", "Markdown content")
                )
        );
    }

    @Override
    public String execute(Map<String, Object> args) {
        try {
            String path = (String) args.get("path");
            String title = (String) args.get("title");
            String content = (String) args.get("content");
            Document doc = knowledgeBaseService.createNote(path, title, content);
            return "Created note '" + doc.getTitle() + "' at " + doc.getPath() + " (id: " + doc.getId() + ")";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
