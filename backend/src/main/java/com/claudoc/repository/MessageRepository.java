package com.claudoc.repository;

import com.claudoc.model.Message;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface MessageRepository extends JpaRepository<Message, String> {

    List<Message> findByConversationIdAndArchivedFalseAndLevelOrderByCreatedAtAsc(
            String conversationId, int level);

    List<Message> findByConversationIdAndArchivedFalseOrderByCreatedAtAsc(
            String conversationId);

    long countByConversationIdAndArchivedFalseAndLevel(String conversationId, int level);
}
