package edu.northeastern.agent_a.llm;

import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import edu.northeastern.agent_a.core.tools.ActionSpec;
import edu.northeastern.agent_a.core.tools.Plan;
import edu.northeastern.agent_a.core.tools.RiskLevel;

/**
 * Mock implementation that simulates an LLM by keyword-matching the user query
 * and returning deterministic function-call steps.
 */
public class MockLLMClient implements LLMClient {

    private static final String TAG = "MockLLMClient";

    private static final Pattern PHONE_PATTERN =
            Pattern.compile("(\\+?\\d[\\d\\s\\-()]{5,}\\d)");

    private static final String HELP_TEXT =
            "I can help you with:\n"
                    + "\u2022 Call / dial a number or contact\n"
                    + "\u2022 Send a text message (SMS)\n"
                    + "\u2022 Navigate to a destination\n"
                    + "\u2022 Check email inbox summary\n"
                    + "\u2022 Fetch news\n"
                    + "\u2022 Compose an email";

    @Override
    public Plan call(LLMRequest request) {
        Log.d(TAG, "MockLLM processing query: " + request.getUserQuery());

        String userText = request.getUserQuery();
        String lower = userText.toLowerCase().trim();

        if (matchesAny(lower, "call", "dial")) {
            return planCall(userText);
        }
        if (matchesAny(lower, "text ", "sms ", "message ")) {
            return planSms(userText);
        }
        if (matchesAny(lower, "navigate", "directions")) {
            return planNavigation(userText);
        }
        // KEY FIX: Only trigger email.summary for reading requests.
        // If it contains "send", "compose", or "draft", we skip and let fallback handle it.
        if (matchesAny(lower, "email", "mail") && !matchesAny(lower, "send", "compose", "draft", "write")) {
            if (matchesAny(lower, "inbox", "summary", "check", "what's in")) {
                return planEmailSummary();
            }
        }
        if (matchesAny(lower, "news", "headlines")) {
            return planNews(userText);
        }

        // If no match, return empty plan to trigger fallback in AgentChatActivity
        return new Plan(Collections.emptyList(), "");
    }

    private Plan planCall(String userText) {
        String phone = extractPhone(userText);
        if (phone != null) {
            return new Plan(stepsOf(step("phone.dial", argsOf("phone", phone), RiskLevel.LOW, "phone.dial")), "Opening dialer...");
        }
        return new Plan(Collections.emptyList(), "");
    }

    private Plan planSms(String userText) {
        String body = extractSmsBody(userText);
        String name = extractSmsRecipientName(userText);
        if (name != null) {
            List<ActionSpec> actions = new ArrayList<>();
            actions.add(step("contacts.lookup", argsOf("name", name), RiskLevel.MEDIUM, "Looking up contact"));
            actions.add(step("sms.compose", argsOf("phone", "[from_lookup]", "body", body), RiskLevel.MEDIUM, "Composing SMS"));
            return new Plan(actions, "Preparing to send text...");
        }
        return new Plan(Collections.emptyList(), "");
    }

    private Plan planNavigation(String userText) {
        String dest = extractAfterKeywords(userText, "navigate to", "navigate", "directions to");
        if (dest != null) {
            return new Plan(stepsOf(step("maps.navigate", argsOf("destination", dest), RiskLevel.LOW, "Starting navigation")), "Navigating...");
        }
        return new Plan(Collections.emptyList(), "");
    }

    private Plan planEmailSummary() {
        return new Plan(stepsOf(step("email.summary", argsOf(), RiskLevel.MEDIUM, "Summarizing emails")), "Fetching your inbox summary...");
    }

    private Plan planNews(String userText) {
        return new Plan(stepsOf(step("news.fetch", argsOf("category", "general"), RiskLevel.LOW, "Fetching news")), "Here is the news:");
    }

    private boolean matchesAny(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }

    private String extractPhone(String text) {
        Matcher m = PHONE_PATTERN.matcher(text);
        if (m.find()) return m.group(1).replaceAll("[\\s()]", "");
        return null;
    }

    private String extractAfterKeywords(String text, String... keywords) {
        String lower = text.toLowerCase();
        for (String kw : keywords) {
            int idx = lower.indexOf(kw.toLowerCase());
            if (idx >= 0) {
                return text.substring(idx + kw.length()).trim();
            }
        }
        return null;
    }

    private String extractSmsBody(String text) {
        Pattern p = Pattern.compile("(?:saying|say|:)\\s+(.+)", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1).trim() : "";
    }

    private String extractSmsRecipientName(String text) {
        String[] keywords = {"to ", "text ", "sms "};
        for (String kw : keywords) {
            int idx = text.toLowerCase().indexOf(kw);
            if (idx >= 0) {
                String after = text.substring(idx + kw.length()).trim();
                return after.split("\\s+")[0];
            }
        }
        return null;
    }

    private ActionSpec step(String tool, Map<String, String> args, RiskLevel risk, String label) {
        return new ActionSpec(tool, args, risk, label, 1, label);
    }

    private Map<String, String> argsOf(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length - 1; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private List<ActionSpec> stepsOf(ActionSpec... specs) {
        List<ActionSpec> list = new ArrayList<>();
        Collections.addAll(list, specs);
        return list;
    }
}
