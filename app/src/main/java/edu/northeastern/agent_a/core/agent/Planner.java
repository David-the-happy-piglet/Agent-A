package edu.northeastern.agent_a.core.agent;

import android.util.Log;

import edu.northeastern.agent_a.core.memory.SessionStore;
import edu.northeastern.agent_a.core.tools.Plan;
import edu.northeastern.agent_a.core.tools.ToolRegistry;
import edu.northeastern.agent_a.llm.LLMClient;
import edu.northeastern.agent_a.llm.LLMRequest;

/**
 * Planner turns the user's text into a Plan.
 * It builds a prompt, sends it to the LLM, and returns the result.
 * It does NOT run any tools — that is Executor's job.
 */
public class Planner {

    private static final String TAG = "Planner";

    // Not final so we can swap the LLM backend at runtime (Improvement #2).
    private LLMClient llmClient;
    private final PromptBuilder promptBuilder;
    private final ToolRegistry registry;

    /**
     * @param llmClient the LLM backend to use for planning
     * @param registry  all registered tools, passed to PromptBuilder to describe available actions
     */
    public Planner(LLMClient llmClient, ToolRegistry registry) {
        this.llmClient = llmClient;
        this.promptBuilder = new PromptBuilder();
        this.registry = registry;
    }

    // ── LLM Switcher (Improvement #2) ────────────────────────────────────

    /**
     * Replaces the active LLM backend without restarting the Activity.
     * Called by the Spinner in AgentChatActivity when the user selects a new LLM.
     * The next call to createPlan() will use the new client.
     *
     * @param llmClient the new LLM backend to use
     */
    public void setLlmClient(LLMClient llmClient) {
        this.llmClient = llmClient;
    }

    // ── Planning ──────────────────────────────────────────────────────────

    /**
     * Builds the full prompt, sends it to the LLM, and returns the resulting Plan.
     * Also saves the plan summary to the session so future prompts have context.
     *
     * @param userText the user's natural language input
     * @param session  the current session, used for chat history and storing the summary
     * @return a Plan containing a list of actions and an assistant message
     */
    public Plan createPlan(String userText, SessionStore session) {
        // Combine tool specs + chat history + user query into one prompt
        LLMRequest request = promptBuilder.build(
                userText, session, registry.getAllSpecs());

        Log.d(TAG, "Full prompt:\n" + request.getFullPrompt());

        // Send to whichever LLM is currently active
        Plan plan = llmClient.call(request);

        // Save summary so future prompts know what happened last
        session.setLastPlanSummary(plan.getAssistantMessage());
        return plan;
    }
}