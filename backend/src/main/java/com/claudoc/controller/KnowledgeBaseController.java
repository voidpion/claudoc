package com.claudoc.controller;

import com.claudoc.model.Chunk;
import com.claudoc.model.Document;
import com.claudoc.service.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    @GetMapping("/notes/tree")
    public List<KnowledgeBaseService.TreeNode> getTree() {
        return knowledgeBaseService.listTree();
    }

    @GetMapping("/notes")
    public List<Document> listNotes() {
        return knowledgeBaseService.listNotes();
    }

    @GetMapping("/notes/{id}")
    public ResponseEntity<Document> getNote(@PathVariable String id) {
        try {
            return ResponseEntity.ok(knowledgeBaseService.getNote(id));
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/notes")
    public Document createNote(@RequestBody Map<String, String> body) {
        return knowledgeBaseService.createNote(
                body.get("path"),
                body.get("title"),
                body.get("content")
        );
    }

    @PutMapping("/notes/{id}")
    public ResponseEntity<Document> updateNote(@PathVariable String id, @RequestBody Map<String, String> body) {
        try {
            return ResponseEntity.ok(knowledgeBaseService.updateNote(id, body.get("title"), body.get("content")));
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/notes/{id}")
    public ResponseEntity<Void> deleteNote(@PathVariable String id) {
        try {
            knowledgeBaseService.deleteNote(id);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/search")
    public List<Document> search(@RequestParam String q) {
        return knowledgeBaseService.search(q);
    }

    @GetMapping("/retrieve")
    public List<Map<String, Object>> retrieve(
            @RequestParam String q,
            @RequestParam(defaultValue = "5") int k) {
        return knowledgeBaseService.retrieve(q, k).stream().map(r -> Map.<String, Object>of(
                "document", Map.of(
                        "id", r.document().getId(),
                        "path", r.document().getPath(),
                        "title", r.document().getTitle()
                ),
                "chunk", Map.of(
                        "content", r.chunk().getContent(),
                        "chunkIndex", r.chunk().getChunkIndex()
                ),
                "score", r.score()
        )).toList();
    }

    @GetMapping("/notes/{id}/chunks")
    public List<Chunk> getChunks(@PathVariable String id) {
        return knowledgeBaseService.getChunks(id);
    }

    // ── Trash endpoints ──

    @GetMapping("/trash")
    public List<Document> listTrash() {
        return knowledgeBaseService.listTrash();
    }

    @PostMapping("/trash/{id}/restore")
    public ResponseEntity<Document> restoreNote(@PathVariable String id) {
        try {
            return ResponseEntity.ok(knowledgeBaseService.restoreNote(id));
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/trash/{id}")
    public ResponseEntity<Void> permanentDelete(@PathVariable String id) {
        try {
            knowledgeBaseService.permanentDelete(id);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }
}
