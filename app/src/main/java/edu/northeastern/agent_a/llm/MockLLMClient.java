package edu.northeastern.agent_a.llm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import edu.northeastern.agent_a.core.memory.SessionStore;
import edu.northeastern.agent_a.core.tools.ActionSpec;
import edu.northeastern.agent_a.core.tools.Plan;
import edu.northeastern.agent_a.core.tools.RiskLevel;

public class MockLLMClient implements LLMClient {

    private static final Pattern PHONE_PATTERN =
            Pattern.compile("(\\+?\\d[\\d\\s\\-()]{5,}\\d)");

    private static final String HELP_TEXT =
            "I can help you with:\n"
                    + "• Call / dial a number or contact\n"
                    + "• Send a text message (SMS)\n"
                    + "• Navigate to a destination\n"
                    + "• Check email inbox summary\n\n"
                    + "Try: \"Call 555-1234\", \"Text 555-1234 hello\", "
                    + "\"Navigate to Boston\", or \"Show my emails\".";

    @Override
    public Plan plan(String userText, SessionStore session) {
        String lower = userText.toLowerCase().trim();

        if (matchesAny(lower, "call", "dial", "拨打", "打电话")) {
            return planCall(userText, lower);
        }
        if (matchesAny(lower, "text ", "sms ", "send text", "send sms",
                "短信", "发短信", "message ")) {
            return planSms(userText, lower);
        }
        if (matchesAny(lower, "navigate", "导航", "directions")) {
            return planNavigation(userText,
                    "navigate to", "navigate", "导航到", "导航", "directions to", "directions");
        }
        if (matchesAny(lower, "去", "到")
                && !matchesAny(lower, "call", "dial", "text", "sms", "收到")) {
            return planNavigation(userText, "去", "到");
        }
        if (matchesAny(lower, "email", "邮件", "inbox", "收件箱", "mail")) {
            return planEmail();
        }

        return new Plan(Collections.emptyList(), HELP_TEXT);
    }

    private Plan planCall(String userText, String lower) {
        String phone = extractPhone(userText);
        if (phone != null) {
            return new Plan(
                    listOf(action("phone.dial", mapOf("phone", phone),
                            RiskLevel.LOW, "Dial " + phone)),
                    "I'll open the dialer for you:");
        }
        String name = extractAfterKeywords(userText, "call", "dial", "拨打", "打电话给", "打电话");
        if (name != null && !name.isEmpty()) {
            return new Plan(
                    listOf(action("contacts.lookup", mapOf("name", name),
                            RiskLevel.MEDIUM, "Look up contact: " + name)),
                    "I'll look up the contact for you:");
        }
        return new Plan(Collections.emptyList(),
                "I'd like to make a call for you. Please provide a phone number or contact name.");
    }

    private Plan planSms(String userText, String lower) {
        String phone = extractPhone(userText);
        String body = extractSmsBody(userText);
        if (phone != null) {
            return new Plan(
                    listOf(action("sms.compose", mapOf("phone", phone, "body", body),
                            RiskLevel.MEDIUM, "Compose SMS to " + phone)),
                    "I'll compose a text message:");
        }
        String name = extractSmsRecipientName(userText);
        if (name != null && !name.isEmpty()) {
            List<ActionSpec> actions = new ArrayList<>();
            actions.add(action("contacts.lookup", mapOf("name", name),
                    RiskLevel.MEDIUM, "Look up contact: " + name));
            actions.add(action("sms.compose", mapOf("phone", "[from_lookup]", "body", body),
                    RiskLevel.MEDIUM, "Compose SMS to " + name));
            return new Plan(actions, "I'll look up the contact, then compose an SMS:");
        }
        return new Plan(Collections.emptyList(),
                "I'd like to send a text for you. Please provide a phone number or contact name.");
    }

    private Plan planNavigation(String userText, String... keywords) {
        String dest = extractAfterKeywords(userText, keywords);
        if (dest != null && !dest.isEmpty()) {
            return new Plan(
                    listOf(action("maps.navigate", mapOf("destination", dest),
                            RiskLevel.LOW, "Navigate to " + dest)),
                    "I'll start navigation:");
        }
        return new Plan(Collections.emptyList(),
                "Where would you like to navigate? Please provide a destination.");
    }

    private Plan planEmail() {
        return new Plan(
                listOf(action("email.summary", mapOf("mode", "inbox"),
                        RiskLevel.LOW, "Show email inbox summary")),
                "Here's your email summary:");
    }

    // --- Parsing helpers ---

    private boolean matchesAny(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }

    private String extractPhone(String text) {
        Matcher m = PHONE_PATTERN.matcher(text);
        if (m.find()) {
            return m.group(1).replaceAll("[\\s()]", "");
        }
        return null;
    }

    private String extractAfterKeywords(String text, String... keywords) {
        String lower = text.toLowerCase();
        for (String kw : keywords) {
            int idx = lower.indexOf(kw.toLowerCase());
            if (idx >= 0) {
                String after = text.substring(idx + kw.length()).trim();
                // Remove leading prepositions/particles
                after = after.replaceFirst("^(to |for |给)", "").trim();
                if (!after.isEmpty()) return after;
            }
        }
        return null;
    }

    private String extractSmsBody(String text) {
        // Look for content after "saying", ":", or quoted text
        Pattern sayingPattern = Pattern.compile("(?:saying|say|with message|:)\\s+(.+)", Pattern.CASE_INSENSITIVE);
        Matcher m = sayingPattern.matcher(text);
        if (m.find()) return m.group(1).trim();

        // Look for quoted text
        Pattern quotePattern = Pattern.compile("[\"'](.+?)[\"']");
        m = quotePattern.matcher(text);
        if (m.find()) return m.group(1);

        // Fallback: text after the phone number or name
        String phone = extractPhone(text);
        if (phone != null) {
            int phoneEnd = text.indexOf(phone) + phone.length();
            String after = text.substring(phoneEnd).trim();
            if (!after.isEmpty()) return after;
        }
        return "";
    }

    private String extractSmsRecipientName(String text) {
        String lower = text.toLowerCase();
        String[] keywords = {"text", "sms", "message", "send text to", "send sms to",
                "发短信给", "发短信", "短信"};
        for (String kw : keywords) {
            int idx = lower.indexOf(kw);
            if (idx >= 0) {
                String after = text.substring(idx + kw.length()).trim();
                after = after.replaceFirst("^(to |给)", "").trim();
                // Take the first word as the name (before "saying" or other markers)
                String[] parts = after.split("\\s+(saying|say|with|:|\")", 2);
                if (parts.length > 0 && !parts[0].isEmpty()) {
                    String candidate = parts[0].trim();
                    if (!PHONE_PATTERN.matcher(candidate).matches()) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    // --- Builder helpers ---

    private ActionSpec action(String tool, Map<String, String> args,
                              RiskLevel risk, String desc) {
        return new ActionSpec(tool, args, risk, desc);
    }

    private Map<String, String> mapOf(String... keyValues) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < keyValues.length - 1; i += 2) {
            m.put(keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    private List<ActionSpec> listOf(ActionSpec... specs) {
        List<ActionSpec> list = new ArrayList<>();
        Collections.addAll(list, specs);
        return list;
    }
}
