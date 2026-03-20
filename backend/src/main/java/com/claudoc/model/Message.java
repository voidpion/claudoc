package com.claudoc.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "message")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Message {

    @Id
    private String id;

    @Column(name = "conversation_id", nullable = false)
    private String conversationId;

    @Column(nullable = false, length = 20)
    private String role;

    @Lob
    private String content;

    @Column(name = "tool_call_id", length = 100)
    private String toolCallId;

    @Column(name = "tool_name", length = 100)
    private String toolName;

    @Lob
    @Column(name = "tool_calls_json")
    private String toolCallsJson;

    @Column(name = "level")
    @Builder.Default
    private int level = 0;

    @Column(name = "archived")
    @Builder.Default
    private boolean archived = false;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
