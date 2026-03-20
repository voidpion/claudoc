package com.claudoc.config;

import com.claudoc.service.KnowledgeBaseService;
import com.claudoc.service.VectorSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements ApplicationRunner {

    private final KnowledgeBaseService knowledgeBaseService;
    private final VectorSearchService vectorSearchService;

    @Override
    public void run(ApplicationArguments args) {
        log.info("Initializing sample knowledge base...");

        knowledgeBaseService.createNote("/notes/welcome.md", "Welcome to Claudoc",
                """
                # Welcome to Claudoc

                Claudoc is a knowledge base system powered by AI. It combines the simplicity of Markdown notes
                with the intelligence of an AI agent.

                ## Features

                - Create and organize Markdown notes
                - Full-text and semantic search
                - AI-powered chat assistant
                - Automatic knowledge retrieval (RAG)

                ## Getting Started

                Start by creating notes or chatting with the AI assistant on the right panel.
                The assistant can help you search, create, and organize your knowledge base.
                """);

        knowledgeBaseService.createNote("/notes/guide/markdown-syntax.md", "Markdown Syntax Guide",
                """
                # Markdown Syntax Guide

                ## Headers

                Use `#` for headers. More `#` symbols create smaller headers.

                ## Emphasis

                - *italic* with single asterisks
                - **bold** with double asterisks
                - ~~strikethrough~~ with double tildes

                ## Lists

                Ordered lists use numbers. Unordered lists use `-`, `*`, or `+`.

                ## Code

                Inline code uses backticks: `code here`.

                Code blocks use triple backticks with optional language identifier.

                ## Links and Images

                Links: [text](url)
                Images: ![alt](url)

                ## Tables

                | Header 1 | Header 2 |
                |----------|----------|
                | Cell 1   | Cell 2   |
                """);

        knowledgeBaseService.createNote("/notes/guide/rag-explained.md", "RAG Explained",
                """
                # Retrieval-Augmented Generation (RAG)

                RAG is a technique that enhances AI responses by retrieving relevant information
                from a knowledge base before generating answers.

                ## How RAG Works

                1. **Chunking**: Documents are split into smaller chunks
                2. **Embedding**: Each chunk is converted to a vector representation
                3. **Indexing**: Vectors are stored in a searchable index
                4. **Retrieval**: When a query comes in, similar chunks are found via vector similarity
                5. **Generation**: Retrieved chunks are provided as context to the LLM

                ## Benefits

                - Reduces hallucination by grounding responses in real data
                - Keeps information up-to-date without retraining
                - Provides source attribution for answers

                ## In Claudoc

                Claudoc implements RAG with:
                - Paragraph-based chunking with overlap
                - TF-IDF mock embeddings (configurable for real embedding APIs)
                - Cosine similarity search
                - Top-K retrieval with relevance scoring
                """);

        knowledgeBaseService.createNote("/notes/ideas/project-roadmap.md", "Project Roadmap",
                """
                # Claudoc Project Roadmap

                ## Phase 1: MVP (Current)
                - Basic knowledge base CRUD
                - RAG-based retrieval
                - Agent chat with tool use
                - Memory compression (L0/L1/L2)

                ## Phase 2: Enhanced
                - Real embedding API support
                - Markdown editor in UI
                - Note linking and backlinks
                - Export/import functionality

                ## Phase 3: Advanced
                - Multi-user support
                - Collaborative editing
                - Plugin system
                - Mobile app
                """);

        knowledgeBaseService.createNote("/_memory/system-init.md", "System Initialization Memory",
                """
                # System Initialization

                This is the initial memory of the Claudoc AI assistant.

                ## Known Facts
                - The knowledge base was initialized with sample notes
                - Available categories: notes, guide, ideas
                - The _memory directory is used for agent long-term memory
                """);

        log.info("Sample knowledge base initialized with 5 notes.");
    }
}
