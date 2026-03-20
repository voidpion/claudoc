package com.claudoc.service;

import com.claudoc.config.AgentConfig;
import com.claudoc.model.Chunk;
import com.claudoc.model.Document;
import com.claudoc.repository.ChunkRepository;
import com.claudoc.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeBaseService {

    private final DocumentRepository documentRepository;
    private final ChunkRepository chunkRepository;
    private final ChunkingService chunkingService;
    private final EmbeddingService embeddingService;
    private final VectorSearchService vectorSearchService;
    private final AgentConfig agentConfig;

    @Transactional
    public Document createNote(String path, String title, String content) {
        Document doc = Document.builder()
                .id(UUID.randomUUID().toString())
                .path(path)
                .title(title)
                .content(content)
                .build();
        documentRepository.save(doc);
        processChunks(doc);
        log.info("Created note: {} at {}", title, path);
        return doc;
    }

    @Transactional
    public Document updateNote(String id, String title, String content) {
        Document doc = documentRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Document not found: " + id));

        if (title != null) doc.setTitle(title);
        if (content != null) doc.setContent(content);
        documentRepository.save(doc);

        // Re-chunk and re-embed
        removeChunks(id);
        processChunks(doc);

        log.info("Updated note: {}", doc.getPath());
        return doc;
    }

    @Transactional
    public void deleteNote(String id) {
        Document doc = documentRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Document not found: " + id));
        // Soft delete: set deleted_at, remove from search index
        removeChunks(id);
        doc.setDeletedAt(LocalDateTime.now());
        documentRepository.save(doc);
        log.info("Soft-deleted note: {}", doc.getPath());
    }

    @Transactional
    public Document restoreNote(String id) {
        Document doc = documentRepository.findDeletedById(id)
                .orElseThrow(() -> new NoSuchElementException("Deleted document not found: " + id));
        doc.setDeletedAt(null);
        documentRepository.save(doc);
        processChunks(doc);
        log.info("Restored note: {}", doc.getPath());
        return doc;
    }

    public List<Document> listTrash() {
        return documentRepository.findByDeletedAtIsNotNull();
    }

    @Transactional
    public void permanentDelete(String id) {
        Document doc = documentRepository.findDeletedById(id)
                .orElseThrow(() -> new NoSuchElementException("Deleted document not found: " + id));
        documentRepository.delete(doc);
        log.info("Permanently deleted note: {}", doc.getPath());
    }

    public Document getNote(String id) {
        return documentRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Document not found: " + id));
    }

    public Optional<Document> getNoteByPath(String path) {
        return documentRepository.findByPath(path);
    }

    public List<Document> listNotes() {
        return documentRepository.findAll();
    }

    public List<TreeNode> listTree() {
        List<String> paths = documentRepository.findAllPaths();
        return buildTree(paths);
    }

    public List<Document> search(String keyword) {
        return documentRepository.searchByKeyword(keyword);
    }

    public List<RetrieveResult> retrieve(String query, int topK) {
        double[] queryVector = embeddingService.embed(query);
        List<VectorSearchService.ChunkScore> scores = vectorSearchService.retrieve(queryVector, topK);

        List<RetrieveResult> results = new ArrayList<>();
        for (VectorSearchService.ChunkScore cs : scores) {
            chunkRepository.findById(cs.chunkId()).ifPresent(chunk -> {
                documentRepository.findById(chunk.getDocumentId()).ifPresent(doc -> {
                    results.add(new RetrieveResult(doc, chunk, cs.score()));
                });
            });
        }
        return results;
    }

    public List<Chunk> getChunks(String documentId) {
        return chunkRepository.findByDocumentIdOrderByChunkIndex(documentId);
    }

    private void processChunks(Document doc) {
        List<String> texts = chunkingService.chunk(doc.getContent());
        embeddingService.updateDocumentFrequency(texts);

        for (int i = 0; i < texts.size(); i++) {
            double[] vector = embeddingService.embed(texts.get(i));
            Chunk chunk = Chunk.builder()
                    .id(UUID.randomUUID().toString())
                    .documentId(doc.getId())
                    .content(texts.get(i))
                    .chunkIndex(i)
                    .vector(embeddingService.vectorToJson(vector))
                    .build();
            chunkRepository.save(chunk);
            vectorSearchService.indexChunk(chunk);
        }
    }

    private void removeChunks(String documentId) {
        List<Chunk> existingChunks = chunkRepository.findByDocumentIdOrderByChunkIndex(documentId);
        List<String> existingTexts = existingChunks.stream().map(Chunk::getContent).collect(Collectors.toList());
        embeddingService.removeDocumentFrequency(existingTexts);
        for (Chunk c : existingChunks) {
            vectorSearchService.removeChunk(c.getId());
        }
        chunkRepository.deleteByDocumentId(documentId);
    }

    private List<TreeNode> buildTree(List<String> paths) {
        Map<String, TreeNode> nodeMap = new LinkedHashMap<>();
        TreeNode root = new TreeNode("root", "/", "folder", new ArrayList<>());

        for (String path : paths) {
            String[] parts = path.split("/");
            StringBuilder currentPath = new StringBuilder();
            TreeNode parent = root;

            for (int i = 0; i < parts.length; i++) {
                if (parts[i].isEmpty()) continue;
                currentPath.append("/").append(parts[i]);
                String key = currentPath.toString();
                boolean isFile = (i == parts.length - 1);

                if (!nodeMap.containsKey(key)) {
                    // Find the document id for files
                    String nodeId = key;
                    if (isFile) {
                        Optional<Document> doc = documentRepository.findByPath(path);
                        if (doc.isPresent()) nodeId = doc.get().getId();
                    }
                    TreeNode node = new TreeNode(nodeId, parts[i], isFile ? "file" : "folder", new ArrayList<>());
                    nodeMap.put(key, node);
                    parent.children().add(node);
                }
                parent = nodeMap.get(key);
            }
        }

        return root.children();
    }

    public record TreeNode(String id, String name, String type, List<TreeNode> children) {}
    public record RetrieveResult(Document document, Chunk chunk, double score) {}
}
