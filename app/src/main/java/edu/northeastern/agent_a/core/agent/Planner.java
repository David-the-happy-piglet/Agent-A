package edu.northeastern.agent_a.core.agent;

import android.util.Log;

import edu.northeastern.agent_a.core.memory.SessionStore;
import edu.northeastern.agent_a.core.tools.Plan;
import edu.northeastern.agent_a.core.tools.ToolRegistry;
import edu.northeastern.agent_a.llm.LLMClient;
import edu.northeastern.agent_a.llm.LLMRequest;

public class Planner {

    private static final String TAG = "Planner";

    // Removed `final` to allow hot-swapping the LLM backend at runtime
    private LLMClient llmClient;
    private final PromptBuilder promptBuilder;
    private final ToolRegistry registry;

    public Planner(LLMClient llmClient, ToolRegistry registry) {
        this.llmClient = llmClient;
        this.promptBuilder = new PromptBuilder();
        this.registry = registry;
    }

    /** Replaces the active LLM backend without restarting the Activity. */
    public void setLlmClient(LLMClient llmClient) {
        this.llmClient = llmClient;
    }

    /**
     * Builds a full prompt (tool specs + session context + user query),
     * sends it to the LLM, and returns the resulting Plan.
     */
    public Plan createPlan(String userText, SessionStore session) {
        LLMRequest request = promptBuilder.build(
                userText, session, registry.getAllSpecs());

        Log.d(TAG, "Full prompt:\n" + request.getFullPrompt());

        Plan plan = llmClient.call(request);
        session.setLastPlanSummary(plan.getAssistantMessage());
        return plan;
    }
}