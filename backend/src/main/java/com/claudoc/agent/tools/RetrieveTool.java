package com.claudoc.agent.tools;

import com.claudoc.agent.AgentTool;
import com.claudoc.config.AgentConfig;
import com.claudoc.service.KnowledgeBaseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class RetrieveTool implements AgentTool {

    private final KnowledgeBaseService knowledgeBaseService;
    private final AgentConfig agentConfig;
    private final ObjectMapper objectMapper;

    @Override
    public String name() { return "retrieve"; }

    @Override
    public String description() {
        return "Semantic search: find the most relevant text chunks using vector similarity. "
                + "Use when: user asks a question about a topic and you need to find related information by meaning (not exact keyword). "
                + "This is the primary tool for answering knowledge questions. Prefer this over 'search' for open-ended queries. "
                + "After getting results, use 'read_note' with the doc_id to get full document context if needed. "
                + "Returns: JSON array of chunks with doc_id, doc_path, doc_title, chunk_content, and similarity score.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "required", new String[]{"query"},
                "properties", Map.of(
                        "query", Map.of("type", "string", "description", "Natural language query"),
                        "top_k", Map.of("type", "integer", "description", "Number of results (default 5)")
                )
        );
    }

    @Override
    public String execute(Map<String, Object> args) {
        try {
            String query = (String) args.get("query");
            int topK = args.containsKey("top_k")
                    ? ((Number) args.get("top_k")).intValue()
                    : agentConfig.getRetrieve().getDefaultTopK();

            var results = knowledgeBaseService.retrieve(query, topK);
            var output = results.stream().map(r -> Map.of(
                    "doc_id", r.document().getId(),
                    "doc_path", r.document().getPath(),
                    "doc_title", r.document().getTitle(),
                    "chunk_content", r.chunk().getContent(),
                    "score", String.format("%.4f", r.score())
            )).toList();
            return objectMapper.writeValueAsString(output);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
