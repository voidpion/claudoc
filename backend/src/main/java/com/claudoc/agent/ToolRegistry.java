package com.claudoc.agent;

import com.claudoc.config.AgentConfig;
import com.claudoc.llm.ToolDefinition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Component
@Slf4j
public class ToolRegistry {

    private final Map<String, AgentTool> tools = new LinkedHashMap<>();
    private final ExecutorService toolExecutor = Executors.newCachedThreadPool();
    private final int toolTimeoutSeconds;

    public ToolRegistry(List<AgentTool> agentTools, AgentConfig agentConfig) {
        this.toolTimeoutSeconds = agentConfig.getLoop().getToolTimeoutSeconds();
        for (AgentTool tool : agentTools) {
            tools.put(tool.name(), tool);
            log.info("Registered agent tool: {}", tool.name());
        }
    }

    public AgentTool getTool(String name) {
        return tools.get(name);
    }

    public List<ToolDefinition> getAllDefinitions() {
        return tools.values().stream()
                .map(t -> ToolDefinition.of(t.name(), t.description(), t.parametersSchema()))
                .collect(Collectors.toList());
    }

    public String executeTool(String name, Map<String, Object> args) {
        AgentTool tool = tools.get(name);
        if (tool == null) {
            return "Error: unknown tool '" + name + "'";
        }
        Future<String> future = toolExecutor.submit(() -> tool.execute(args));
        try {
            return future.get(toolTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("Tool execution timed out after {}s: {} with args {}", toolTimeoutSeconds, name, args);
            return "Error: tool '" + name + "' timed out after " + toolTimeoutSeconds + " seconds";
        } catch (ExecutionException e) {
            log.error("Tool execution error: {} with args {}", name, args, e.getCause());
            return "Error executing " + name + ": " + e.getCause().getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Error: tool execution interrupted";
        }
    }
}
