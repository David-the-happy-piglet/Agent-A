package edu.northeastern.agent_a.core.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ToolRegistry {

    private final Map<String, Tool> tools = new HashMap<>();

    public void register(Tool tool) {
        tools.put(tool.name(), tool);
    }

    public Tool get(String name) {
        return tools.get(name);
    }

    public boolean has(String name) {
        return tools.containsKey(name);
    }

    /** Returns specs for all registered tools, used by PromptBuilder. */
    public List<ToolSpec> getAllSpecs() {
        List<ToolSpec> specs = new ArrayList<>();
        for (Tool tool : tools.values()) {
            specs.add(tool.spec());
        }
        return specs;
    }
}
