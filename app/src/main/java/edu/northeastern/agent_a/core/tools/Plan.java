package edu.northeastern.agent_a.core.tools;

import java.util.List;

public class Plan {

    private final List<ActionSpec> actions;
    private final String assistantMessage;

    public Plan(List<ActionSpec> actions, String assistantMessage) {
        this.actions = actions;
        this.assistantMessage = assistantMessage;
    }

    public List<ActionSpec> getActions() { return actions; }
    public String getAssistantMessage() { return assistantMessage; }
    public boolean hasActions() { return actions != null && !actions.isEmpty(); }
}
