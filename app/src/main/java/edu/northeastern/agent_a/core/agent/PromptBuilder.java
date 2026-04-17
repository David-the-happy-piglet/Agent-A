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
                    + "If the user's request can be handled by one or more of the tools listed below, plan it using ONLY those tools.\n"
                    + "This includes calling, texting, navigation, email summary, news fetching, weather lookup, Spotify control, media sharing, and app capability checks.\n"
                    + "If the user is chatting or asking a general question that does not need a tool, answer conversationally and use no tools.\n"
                    + "Return STRICT JSON only with this shape:\n"
                    + "  {\"message\":\"<short assistant reply>\",\"steps\":[{\"tool\":\"<tool_name>\",\"args\":{\"<key>\":\"<value>\"}}]}\n"
                    + "Each step object must use the form:\n"
                    + "  {\"tool\": \"<tool_name>\", \"args\": {\"<key>\": \"<value>\", ...}}\n"
                    + "If a step needs output from a previous step (e.g. a phone number from contacts.lookup), "
                    + "use \"[from_lookup]\" as the placeholder value.\n"
                    + "Never invent tools that are not listed below.\n"
                    + "If no available tool fits the request, return an empty steps array and put your normal reply in \"message\".\n"
                    + "Do not wrap the JSON in markdown fences or add any extra commentary.\n";

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
