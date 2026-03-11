package edu.northeastern.agent_a.llm;

/**
 * Encapsulates everything sent to the LLM in one call:
 * a system prompt (tool definitions + conversation history) and the user's query.
 */
public class LLMRequest {

    private final String systemPrompt;
    private final String userQuery;

    public LLMRequest(String systemPrompt, String userQuery) {
        this.systemPrompt = systemPrompt;
        this.userQuery = userQuery;
    }

    public String getSystemPrompt() { return systemPrompt; }
    public String getUserQuery() { return userQuery; }

    /** The complete text that would be sent to an LLM API. */
    public String getFullPrompt() {
        return systemPrompt + "\n=== USER QUERY ===\n" + userQuery + "\n";
    }
}
