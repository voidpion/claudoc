package com.claudoc.agent.tools;

import com.claudoc.agent.AgentTool;
import com.claudoc.model.Document;
import com.claudoc.service.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class RestoreNoteTool implements AgentTool {

    private final KnowledgeBaseService knowledgeBaseService;

    @Override
    public String name() { return "restore_note"; }

    @Override
    public String description() {
        return "Restore a previously deleted note from trash. "
                + "Use when: user asks to recover/restore/undelete a note. "
                + "Do NOT use on notes that are not in trash. "
                + "Returns: restored document info (id, path, title).";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "required", new String[]{"id"},
                "properties", Map.of(
                        "id", Map.of("type", "string", "description", "ID of the deleted document to restore")
                )
        );
    }

    @Override
    public String execute(Map<String, Object> args) {
        try {
            Document doc = knowledgeBaseService.restoreNote((String) args.get("id"));
            return String.format("Note restored: [%s] %s (path: %s)", doc.getId(), doc.getTitle(), doc.getPath());
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
