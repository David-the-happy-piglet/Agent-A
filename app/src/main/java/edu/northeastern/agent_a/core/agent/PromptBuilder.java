package edu.northeastern.agent_a.core.agent;

import edu.northeastern.agent_a.core.memory.Message;
import edu.northeastern.agent_a.core.memory.SessionStore;

public class PromptBuilder {

    public String buildContext(SessionStore session) {
        StringBuilder sb = new StringBuilder();
        for (Message msg : session.getMessages()) {
            sb.append(msg.getRole().name()).append(": ").append(msg.getText()).append("\n");
        }
        if (!session.getLastPlanSummary().isEmpty()) {
            sb.append("[Last plan: ").append(session.getLastPlanSummary()).append("]\n");
        }
        return sb.toString();
    }
}
