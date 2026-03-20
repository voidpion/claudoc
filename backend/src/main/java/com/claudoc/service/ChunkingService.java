package com.claudoc.service;

import com.claudoc.config.AgentConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ChunkingService {

    private final AgentConfig agentConfig;

    public List<String> chunk(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        int maxSize = agentConfig.getChunking().getMaxChunkSize();
        String[] paragraphs = text.split("\\n\\n+");
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String paragraph : paragraphs) {
            String trimmed = paragraph.trim();
            if (trimmed.isEmpty()) continue;

            if (current.length() + trimmed.length() + 2 > maxSize && !current.isEmpty()) {
                chunks.add(current.toString().trim());
                // overlap: carry last sentence
                String lastSentence = extractLastSentence(current.toString());
                current = new StringBuilder();
                if (lastSentence != null) {
                    current.append(lastSentence).append("\n\n");
                }
            }
            current.append(trimmed).append("\n\n");
        }

        if (!current.isEmpty()) {
            chunks.add(current.toString().trim());
        }

        if (chunks.isEmpty() && !text.isBlank()) {
            chunks.add(text.trim());
        }

        return chunks;
    }

    private String extractLastSentence(String text) {
        String trimmed = text.trim();
        // Try splitting by sentence-ending punctuation
        String[] sentences = trimmed.split("(?<=[.!?。！？])\s+");
        if (sentences.length > 0) {
            return sentences[sentences.length - 1].trim();
        }
        return null;
    }
}
