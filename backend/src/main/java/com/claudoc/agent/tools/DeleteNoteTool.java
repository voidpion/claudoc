package com.claudoc.agent.tools;

import com.claudoc.agent.AgentTool;
import com.claudoc.model.Document;
import com.claudoc.service.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class DeleteNoteTool implements AgentTool {

    private static final String PROTECTED_PREFIX = "/_memory/";

    private final KnowledgeBaseService knowledgeBaseService;

    @Override
    public String name() { return "delete_note"; }

    @Override
    public String description() {
        return "Move a note to the trash (soft delete) by ID. The note can be restored later. "
                + "Use when: user explicitly asks to remove/delete a document. "
                + "Do NOT use to permanently erase — use this for all normal deletions. "
                + "Returns: confirmation that the note was moved to trash.";
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
            String id = (String) args.get("id");
            Document doc = knowledgeBaseService.getNote(id);
            if (doc.getPath() != null && doc.getPath().startsWith(PROTECTED_PREFIX)) {
                return "Error: cannot delete memory file '" + doc.getPath()
                        + "'. Memory notes are protected and cannot be deleted.";
            }
            knowledgeBaseService.deleteNote(id);
            return "Note moved to trash. It can be restored with restore_note.";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
