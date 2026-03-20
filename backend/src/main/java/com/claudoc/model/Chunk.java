package com.claudoc.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "chunk")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Chunk {

    @Id
    private String id;

    @Column(name = "document_id", nullable = false)
    private String documentId;

    @Lob
    private String content;

    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    @Lob
    private String vector;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Transient
    private double[] vectorArray;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
