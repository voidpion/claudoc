package com.claudoc.agent.tools;

import com.claudoc.agent.AgentTool;
import com.claudoc.model.Chunk;
import com.claudoc.service.KnowledgeBaseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class GetChunksTool implements AgentTool {

    private final KnowledgeBaseService knowledgeBaseService;
    private final ObjectMapper objectMapper;

    @Override
    public String name() { return "get_chunks"; }

    @Override
    public String description() {
        return "View the chunks of a specific document (useful for debugging RAG).";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "required", new String[]{"document_id"},
                "properties", Map.of(
                        "document_id", Map.of("type", "string", "description", "Document ID")
                )
        );
    }

    @Override
    public String execute(Map<String, Object> args) {
        try {
            String docId = (String) args.get("document_id");
            List<Chunk> chunks = knowledgeBaseService.getChunks(docId);
            var output = chunks.stream().map(c -> Map.of(
                    "chunk_index", c.getChunkIndex(),
                    "content", c.getContent(),
                    "content_length", c.getContent().length()
            )).toList();
            return objectMapper.writeValueAsString(output);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
