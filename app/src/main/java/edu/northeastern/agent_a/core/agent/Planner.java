package edu.northeastern.agent_a.core.agent;

import edu.northeastern.agent_a.core.memory.SessionStore;
import edu.northeastern.agent_a.core.tools.Plan;
import edu.northeastern.agent_a.llm.LLMClient;

public class Planner {

    private final LLMClient llmClient;

    public Planner(LLMClient llmClient) {
        this.llmClient = llmClient;
    }

    public Plan createPlan(String userText, SessionStore session) {
        Plan plan = llmClient.plan(userText, session);
        session.setLastPlanSummary(plan.getAssistantMessage());
        return plan;
    }
}
