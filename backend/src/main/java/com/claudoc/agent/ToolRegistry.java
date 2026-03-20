package com.claudoc.agent;

import com.claudoc.llm.ToolDefinition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
@Slf4j
public class ToolRegistry {

    private final Map<String, AgentTool> tools = new LinkedHashMap<>();

    public ToolRegistry(List<AgentTool> agentTools) {
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
        try {
            return tool.execute(args);
        } catch (Exception e) {
            log.error("Tool execution error: {} with args {}", name, args, e);
            return "Error executing " + name + ": " + e.getMessage();
        }
    }
}
