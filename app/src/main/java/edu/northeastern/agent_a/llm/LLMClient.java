package edu.northeastern.agent_a.llm;

import edu.northeastern.agent_a.core.tools.Plan;

public interface LLMClient {
    /**
     * Sends a structured request (system prompt + user query) to the LLM
     * and returns a Plan consisting of function-call steps.
     */
    Plan call(LLMRequest request);
}
