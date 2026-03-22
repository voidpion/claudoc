package com.claudoc.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "llm")
public class LlmConfig {

    private String provider = "anthropic";
    private String baseUrl;
    private String apiKey;
    private String model;
    private int maxTokens = 4096;
    /** Model context window size (tokens). Used to enforce hard cap on context. */
    private int contextWindow = 8000;
    private double temperature = 0.7;
    private String userAgent = "claude-cli/2.1.80";
    private String anthropicVersion = "2023-06-01";
}
