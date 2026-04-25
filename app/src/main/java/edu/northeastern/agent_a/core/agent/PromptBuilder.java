package edu.northeastern.agent_a.core.agent;

import java.util.List;
import java.util.Map;

import edu.northeastern.agent_a.core.memory.Message;
import edu.northeastern.agent_a.core.memory.SessionStore;
import edu.northeastern.agent_a.core.tools.ToolSpec;
import edu.northeastern.agent_a.llm.LLMRequest;

/**
 * Constructs the full prompt with multi-turn protocol stability.
 */
public class PromptBuilder {

    public LLMRequest build(String userQuery, SessionStore session, List<ToolSpec> toolSpecs) {
        StringBuilder prompt = new StringBuilder();

        // 1. Multi-Turn Protocol Instructions
        prompt.append("### SYSTEM PROTOCOL ###\n")
              .append("You are Agent-A, a sophisticated Android Task Planner.\n\n")
              .append("### 1. CURRENT CONTEXT SNAPSHOT\n")
              .append("[LAST_ACTION_STATUS]: ").append(session.getLastToolOutput()).append("\n")
              .append("[PENDING_STEPS]: ").append(session.getRemainingCompoundCommands()).append("\n")
              .append("[USER_PREFERENCES]: ").append(formatPreferences(session.getUserPreferences())).append("\n\n")
              .append("### 2. CONTEXT CLEANING & CLASSIFICATION RULES\n")
              .append("- FOCUS ONLY on the most recent prompt. Ignore previous greetings.\n")
              .append("- READ vs COMPOSE: If the user wants to READ, CHECK, or SUMMARIZE emails, use 'email.summary'. If the user wants to SEND, COMPOSE, or DRAFT an email, use 'gmail.compose'.\n")
              .append("- DO NOT use 'email.summary' if the intent is to write a new message.\n")
              .append("- If a parameter (like email body) is missing, GENERATE high-quality professional content.\n")
              .append("- If you are truly unsure which tool to use, call 'system.clarify' with a question.\n\n")
              .append("### 3. OUTPUT FORMAT\n")
              .append("- Return ONLY a JSON array of steps. No conversational text.\n")
              .append("- Each step: {\"step\": int, \"label\": string, \"tool\": string, \"parameters\": map}\n\n");

        // 2. Registered Tools
        prompt.append("### 4. REGISTERED TOOLS ###\n");
        for (ToolSpec ts : toolSpecs) {
            prompt.append("- ").append(ts.toPromptString()).append("\n");
        }
        prompt.append("- system.clarify(question: String) — Asks the user for missing information.\n\n");

        // 3. Examples for clear distinction
        prompt.append("### 5. EXAMPLES ###\n")
              .append("User: \"What's in my gmail?\"\n")
              .append("Output: [{\"step\": 1, \"label\": \"Summarizing emails\", \"tool\": \"email.summary\", \"parameters\": {}}]\n\n")
              .append("User: \"compose an email with subject line: x and send to y and say z\"\n")
              .append("Output: [{\"step\": 1, \"label\": \"Preparing email draft\", \"tool\": \"gmail.compose\", \"parameters\": {\"recipient\": \"y\", \"subject\": \"x\", \"body\": \"z\"}}]\n\n");

        // 4. Recent History
        List<Message> messages = session.getMessages();
        if (!messages.isEmpty()) {
            prompt.append("### 6. RECENT HISTORY (Strictly last 3 turns) ###\n");
            int start = Math.max(0, messages.size() - 6);
            for (int i = start; i < messages.size(); i++) {
                Message msg = messages.get(i);
                prompt.append(msg.getRole().name()).append(": ").append(msg.getText()).append("\n");
            }
            prompt.append("\n");
        }

        prompt.append("### USER QUERY ###\n").append(userQuery);

        return new LLMRequest(prompt.toString(), userQuery);
    }

    private String formatPreferences(Map<String, String> prefs) {
        if (prefs.isEmpty()) return "None";
        return prefs.toString();
    }
}
