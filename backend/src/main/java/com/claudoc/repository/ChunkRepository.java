package com.claudoc.repository;

import com.claudoc.model.Chunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface ChunkRepository extends JpaRepository<Chunk, String> {

    List<Chunk> findByDocumentIdOrderByChunkIndex(String documentId);

    void deleteByDocumentId(String documentId);
}
