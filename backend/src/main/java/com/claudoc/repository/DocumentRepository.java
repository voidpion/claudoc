package com.claudoc.repository;

import com.claudoc.model.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface DocumentRepository extends JpaRepository<Document, String> {

    @Query("SELECT d FROM Document d WHERE d.path = :path AND d.deletedAt IS NULL")
    Optional<Document> findByPath(String path);

    @Query(value = "SELECT * FROM document WHERE deleted_at IS NULL AND (LOWER(content) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
           "OR LOWER(title) LIKE LOWER(CONCAT('%', :keyword, '%')))", nativeQuery = true)
    List<Document> searchByKeyword(String keyword);

    @Query("SELECT d FROM Document d WHERE d.path LIKE CONCAT(:pathPrefix, '%') AND d.deletedAt IS NULL")
    List<Document> findByPathStartingWith(String pathPrefix);

    @Query("SELECT DISTINCT d.path FROM Document d WHERE d.deletedAt IS NULL ORDER BY d.path")
    List<String> findAllPaths();

    // Trash queries
    List<Document> findByDeletedAtIsNotNull();

    @Query("SELECT d FROM Document d WHERE d.id = :id AND d.deletedAt IS NOT NULL")
    Optional<Document> findDeletedById(String id);
}
