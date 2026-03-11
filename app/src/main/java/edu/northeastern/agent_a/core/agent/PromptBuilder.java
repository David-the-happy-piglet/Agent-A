package edu.northeastern.agent_a.core.agent;

import java.util.List;

import edu.northeastern.agent_a.core.memory.Message;
import edu.northeastern.agent_a.core.memory.SessionStore;
import edu.northeastern.agent_a.core.tools.ToolSpec;
import edu.northeastern.agent_a.llm.LLMRequest;

/**
 * Constructs the full prompt that is sent to the LLM on every call.
 * The prompt contains: system instructions, available tool signatures,
 * conversation history, and the current user query.
 */
public class PromptBuilder {

    private static final String SYSTEM_INSTRUCTIONS =
            "You are an AI agent running on an Android phone.\n"
                    + "Plan the user's request using ONLY the tools listed below.\n"
                    + "Return a JSON array of steps. Each step is:\n"
                    + "  {\"tool\": \"<tool_name>\", \"args\": {\"<key>\": \"<value>\", ...}}\n"
                    + "If a step needs output from a previous step (e.g. a phone number from contacts.lookup), "
                    + "use \"[from_lookup]\" as the placeholder value.\n"
                    + "If the request is unclear or unsupported, return an empty array "
                    + "and include a \"message\" field explaining what you can do.\n";

    public LLMRequest build(String userQuery, SessionStore session,
                            List<ToolSpec> toolSpecs) {
        StringBuilder prompt = new StringBuilder();

        // 1. System instructions
        prompt.append("=== SYSTEM ===\n");
        prompt.append(SYSTEM_INSTRUCTIONS).append("\n");

        // 2. Available tools (function signatures)
        prompt.append("=== AVAILABLE TOOLS ===\n");
        for (int i = 0; i < toolSpecs.size(); i++) {
            prompt.append(i + 1).append(". ")
                    .append(toolSpecs.get(i).toPromptString()).append("\n");
        }

        // 3. Conversation history
        List<Message> messages = session.getMessages();
        if (!messages.isEmpty()) {
            prompt.append("=== CONVERSATION HISTORY ===\n");
            for (Message msg : messages) {
                prompt.append(msg.getRole().name()).append(": ")
                        .append(msg.getText()).append("\n");
            }
            prompt.append("\n");
        }

        return new LLMRequest(prompt.toString(), userQuery);
    }
}
