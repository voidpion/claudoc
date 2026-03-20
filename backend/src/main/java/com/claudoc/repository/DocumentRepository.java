package com.claudoc.repository;

import com.claudoc.model.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface DocumentRepository extends JpaRepository<Document, String> {

    Optional<Document> findByPath(String path);

    @Query(value = "SELECT * FROM document WHERE LOWER(content) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
           "OR LOWER(title) LIKE LOWER(CONCAT('%', :keyword, '%'))", nativeQuery = true)
    List<Document> searchByKeyword(String keyword);

    List<Document> findByPathStartingWith(String pathPrefix);

    @Query("SELECT DISTINCT d.path FROM Document d ORDER BY d.path")
    List<String> findAllPaths();
}
