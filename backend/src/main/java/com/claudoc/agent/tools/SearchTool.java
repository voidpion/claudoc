package com.claudoc.agent.tools;

import com.claudoc.agent.AgentTool;
import com.claudoc.model.Document;
import com.claudoc.service.KnowledgeBaseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class SearchTool implements AgentTool {

    private final KnowledgeBaseService knowledgeBaseService;
    private final ObjectMapper objectMapper;

    @Override
    public String name() { return "search"; }

    @Override
    public String description() {
        return "Keyword search: find notes by exact keyword match in title and content (SQL LIKE). "
                + "Use when: user asks to find notes containing a specific term, or you need to check if a note about a topic already exists. "
                + "Do NOT use for semantic/meaning-based queries — use 'retrieve' instead. "
                + "Returns: JSON array of matching documents with id, path, title, and a 200-char snippet.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "required", new String[]{"keyword"},
                "properties", Map.of(
                        "keyword", Map.of("type", "string", "description", "Search keyword")
                )
        );
    }

    @Override
    public String execute(Map<String, Object> args) {
        try {
            String keyword = (String) args.get("keyword");
            List<Document> results = knowledgeBaseService.search(keyword);
            var summaries = results.stream().map(d -> Map.of(
                    "id", d.getId(),
                    "path", d.getPath(),
                    "title", d.getTitle(),
                    "snippet", d.getContent().substring(0, Math.min(200, d.getContent().length()))
            )).toList();
            return objectMapper.writeValueAsString(summaries);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
