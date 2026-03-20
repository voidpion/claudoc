package com.claudoc.agent;

import java.util.Map;

public interface AgentTool {

    String name();

    String description();

    Map<String, Object> parametersSchema();

    String execute(Map<String, Object> args);
}
