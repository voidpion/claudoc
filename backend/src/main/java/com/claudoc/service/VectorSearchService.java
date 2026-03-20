package com.claudoc.service;

import com.claudoc.model.Chunk;
import com.claudoc.repository.ChunkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class VectorSearchService {

    private final ChunkRepository chunkRepository;
    private final EmbeddingService embeddingService;

    // In-memory vector index: chunkId -> vector
    private final Map<String, double[]> vectorIndex = new ConcurrentHashMap<>();

    public void indexChunk(Chunk chunk) {
        double[] vector = embeddingService.jsonToVector(chunk.getVector());
        vectorIndex.put(chunk.getId(), vector);
    }

    public void removeChunk(String chunkId) {
        vectorIndex.remove(chunkId);
    }

    public void rebuildIndex() {
        vectorIndex.clear();
        List<Chunk> allChunks = chunkRepository.findAll();
        for (Chunk chunk : allChunks) {
            if (chunk.getVector() != null) {
                vectorIndex.put(chunk.getId(), embeddingService.jsonToVector(chunk.getVector()));
            }
        }
        log.info("Vector index rebuilt with {} chunks", vectorIndex.size());
    }

    public List<ChunkScore> retrieve(double[] queryVector, int topK) {
        PriorityQueue<ChunkScore> heap = new PriorityQueue<>(
                Comparator.comparingDouble(ChunkScore::score)
        );

        for (Map.Entry<String, double[]> entry : vectorIndex.entrySet()) {
            double similarity = embeddingService.cosineSimilarity(queryVector, entry.getValue());
            if (heap.size() < topK) {
                heap.offer(new ChunkScore(entry.getKey(), similarity));
            } else if (similarity > heap.peek().score()) {
                heap.poll();
                heap.offer(new ChunkScore(entry.getKey(), similarity));
            }
        }

        List<ChunkScore> results = new ArrayList<>(heap);
        results.sort((a, b) -> Double.compare(b.score(), a.score()));
        return results;
    }

    public record ChunkScore(String chunkId, double score) {}
}
