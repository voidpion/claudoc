package com.claudoc.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "agent")
public class AgentConfig {

    private MemoryConfig memory = new MemoryConfig();
    private RetrieveConfig retrieve = new RetrieveConfig();
    private ChunkingConfig chunking = new ChunkingConfig();
    private LoopConfig loop = new LoopConfig();

    @Data
    public static class MemoryConfig {
        private int l0MaxTokens = 4000;
        private int l1MaxCount = 5;
    }

    @Data
    public static class RetrieveConfig {
        private int defaultTopK = 5;
    }

    @Data
    public static class ChunkingConfig {
        private int maxChunkSize = 500;
        private int overlapSentences = 1;
    }

    @Data
    public static class LoopConfig {
        /** Maximum concurrent agent loops */
        private int maxConcurrent = 5;
        /** Tool execution timeout in seconds */
        private int toolTimeoutSeconds = 30;
        /** Maximum tool call iterations per request */
        private int maxIterations = 10;
    }
}
