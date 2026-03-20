package com.claudoc.agent.tools;

import com.claudoc.agent.AgentTool;
import com.claudoc.model.Document;
import com.claudoc.service.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ListTrashTool implements AgentTool {

    private final KnowledgeBaseService knowledgeBaseService;

    @Override
    public String name() { return "list_trash"; }

    @Override
    public String description() {
        return "List all notes currently in the trash (soft-deleted). "
                + "Use when: user asks to see deleted notes, trash, or recycle bin. "
                + "Returns: list of deleted documents with id, path, title, and deletion time.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of()
        );
    }

    @Override
    public String execute(Map<String, Object> args) {
        try {
            List<Document> trash = knowledgeBaseService.listTrash();
            if (trash.isEmpty()) {
                return "Trash is empty.";
            }
            StringBuilder sb = new StringBuilder("Trash (" + trash.size() + " items):\n");
            for (Document doc : trash) {
                sb.append(String.format("- [%s] %s (path: %s, deleted: %s)\n",
                        doc.getId(), doc.getTitle(), doc.getPath(), doc.getDeletedAt()));
            }
            return sb.toString();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
