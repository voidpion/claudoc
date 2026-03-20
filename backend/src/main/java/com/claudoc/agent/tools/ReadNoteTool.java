package com.claudoc.agent.tools;

import com.claudoc.agent.AgentTool;
import com.claudoc.model.Document;
import com.claudoc.service.KnowledgeBaseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class ReadNoteTool implements AgentTool {

    private final KnowledgeBaseService knowledgeBaseService;
    private final ObjectMapper objectMapper;

    @Override
    public String name() { return "read_note"; }

    @Override
    public String description() {
        return "Read the full content of a note by its ID or path.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "id", Map.of("type", "string", "description", "Document ID"),
                        "path", Map.of("type", "string", "description", "Document path (alternative to id)")
                )
        );
    }

    @Override
    public String execute(Map<String, Object> args) {
        try {
            Document doc;
            if (args.containsKey("id")) {
                doc = knowledgeBaseService.getNote((String) args.get("id"));
            } else if (args.containsKey("path")) {
                doc = knowledgeBaseService.getNoteByPath((String) args.get("path"))
                        .orElseThrow(() -> new RuntimeException("Note not found at path: " + args.get("path")));
            } else {
                return "Error: must provide 'id' or 'path'";
            }
            return objectMapper.writeValueAsString(Map.of(
                    "id", doc.getId(),
                    "path", doc.getPath(),
                    "title", doc.getTitle(),
                    "content", doc.getContent()
            ));
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
