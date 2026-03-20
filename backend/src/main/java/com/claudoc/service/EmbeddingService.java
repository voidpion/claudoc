package com.claudoc.service;

import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mock embedding service using TF-IDF style hashing.
 * Maps text to a fixed-dimension vector using hash-based feature extraction.
 */
@Service
public class EmbeddingService {

    private static final int VECTOR_DIM = 256;

    // Global document frequency: word -> number of chunks containing it
    private final Map<String, Integer> documentFrequency = new ConcurrentHashMap<>();
    private int totalDocuments = 0;

    public double[] embed(String text) {
        double[] vector = new double[VECTOR_DIM];
        if (text == null || text.isBlank()) return vector;

        Map<String, Integer> tf = termFrequency(text);
        int maxTf = tf.values().stream().mapToInt(Integer::intValue).max().orElse(1);

        for (Map.Entry<String, Integer> entry : tf.entrySet()) {
            String word = entry.getKey();
            double normalizedTf = 0.5 + 0.5 * entry.getValue() / (double) maxTf;
            double idf = Math.log(1.0 + (totalDocuments + 1) / (1.0 + documentFrequency.getOrDefault(word, 0)));
            double tfidf = normalizedTf * idf;

            // Hash word to a dimension index
            int idx = Math.abs(word.hashCode()) % VECTOR_DIM;
            vector[idx] += tfidf;
        }

        // L2 normalize
        double norm = 0;
        for (double v : vector) norm += v * v;
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < vector.length; i++) {
                vector[i] /= norm;
            }
        }

        return vector;
    }

    public void updateDocumentFrequency(List<String> chunkTexts) {
        for (String text : chunkTexts) {
            Set<String> uniqueWords = new HashSet<>(tokenize(text));
            for (String word : uniqueWords) {
                documentFrequency.merge(word, 1, Integer::sum);
            }
            totalDocuments++;
        }
    }

    public void removeDocumentFrequency(List<String> chunkTexts) {
        for (String text : chunkTexts) {
            Set<String> uniqueWords = new HashSet<>(tokenize(text));
            for (String word : uniqueWords) {
                documentFrequency.computeIfPresent(word, (k, v) -> v > 1 ? v - 1 : null);
            }
            totalDocuments = Math.max(0, totalDocuments - 1);
        }
    }

    public double cosineSimilarity(double[] a, double[] b) {
        if (a.length != b.length) return 0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom > 0 ? dot / denom : 0;
    }

    public String vectorToJson(double[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(String.format("%.6f", vector[i]));
        }
        sb.append("]");
        return sb.toString();
    }

    public double[] jsonToVector(String json) {
        if (json == null || json.isBlank()) return new double[VECTOR_DIM];
        String stripped = json.substring(1, json.length() - 1);
        String[] parts = stripped.split(",");
        double[] vector = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            vector[i] = Double.parseDouble(parts[i].trim());
        }
        return vector;
    }

    private Map<String, Integer> termFrequency(String text) {
        Map<String, Integer> tf = new HashMap<>();
        for (String word : tokenize(text)) {
            tf.merge(word, 1, Integer::sum);
        }
        return tf;
    }

    private List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        // Simple tokenization: split on non-alphanumeric (supports CJK)
        for (String token : text.toLowerCase().split("[\\s\\p{Punct}]+")) {
            if (token.length() >= 1) {
                // For CJK text, split into individual characters
                if (token.matches(".*[\\u4e00-\\u9fff\\u3040-\\u30ff]+.*")) {
                    for (char c : token.toCharArray()) {
                        tokens.add(String.valueOf(c));
                    }
                } else if (token.length() >= 2) {
                    tokens.add(token);
                }
            }
        }
        return tokens;
    }
}
