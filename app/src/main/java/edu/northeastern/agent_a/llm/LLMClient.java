package edu.northeastern.agent_a.llm;

import edu.northeastern.agent_a.core.memory.SessionStore;
import edu.northeastern.agent_a.core.tools.Plan;

public interface LLMClient {
    Plan plan(String userText, SessionStore session);
}
