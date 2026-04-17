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
 * <p>
 * In a real implementation, {@link #call(LLMRequest)} would POST
 * {@code request.getFullPrompt()} to an LLM API and parse the returned JSON
 * into a {@link Plan}.
 */
public class MockLLMClient implements LLMClient {

    private static final String TAG = "MockLLMClient";

    private static final Pattern PHONE_PATTERN =
            Pattern.compile("(\\+?\\d[\\d\\s\\-()]{5,}\\d)");
    private static final Pattern SPOTIFY_URI_PATTERN =
            Pattern.compile("(spotify:(?:track|album|playlist):[A-Za-z0-9]+)", Pattern.CASE_INSENSITIVE);

    private static final String HELP_TEXT =
            "I can help you with:\n"
                    + "\u2022 Call / dial a number or contact\n"
                    + "\u2022 Send a text message (SMS)\n"
                    + "\u2022 Navigate to a destination\n"
                    + "\u2022 Check email inbox summary\n"
                    + "\u2022 Search/play/pause Spotify\n"
                    + "\u2022 Share the latest photo or video from the phone\n"
                    + "\u2022 Check weather\n"
                    + "\u2022 List installed apps or check whether a command can run\n"
                    + "\u2022 Fetch news — supported categories:\n"
                    + "    general \u00b7 tech \u00b7 ai \u00b7 finance \u00b7 politics\n"
                    + "    crypto \u00b7 science \u00b7 security \u00b7 startups\n"
                    + "    energy \u00b7 asia \u00b7 middleeast\n\n"
                    + "Try: \"News today\", \"Play Taylor Swift on Spotify\", "
                    + "\"Send my latest photo to John\", \"Weather in Boston\", "
                    + "or \"Can this phone run Spotify commands?\".";

    @Override
    public Plan call(LLMRequest request) {
        // A real LLM would receive request.getFullPrompt() and return JSON.
        // The mock just parses the user query with keyword rules.
        Log.d(TAG, "Prompt sent to LLM (" + request.getSystemPrompt().length()
                + " chars system + query):\n" + request.getUserQuery());

        String userText = request.getUserQuery();
        String lower = userText.toLowerCase().trim();

        if (matchesAny(lower, "call", "dial", "\u62e8\u6253", "\u6253\u7535\u8bdd")) {
            return planCall(userText);
        }
        if (isMediaShareRequest(lower)) {
            return planMediaShare(userText);
        }
        if (isSpotifyRequest(lower)) {
            return planSpotify(userText);
        }
        if (matchesAny(lower, "weather", "forecast", "\u5929\u6c14")) {
            return planWeather(userText);
        }
        if (isAppCapabilityRequest(lower)) {
            return planAppCapability(userText);
        }
        if (matchesAny(lower, "text ", "sms ", "send text", "send sms",
                "\u77ed\u4fe1", "\u53d1\u77ed\u4fe1", "message ")) {
            return planSms(userText);
        }
        if (matchesAny(lower, "navigate", "\u5bfc\u822a", "directions")) {
            return planNavigation(userText,
                    "navigate to", "navigate", "\u5bfc\u822a\u5230", "\u5bfc\u822a",
                    "directions to", "directions");
        }
        if (matchesAny(lower, "\u53bb", "\u5230")
                && !matchesAny(lower, "call", "dial", "text", "sms", "\u6536\u5230")) {
            return planNavigation(userText, "\u53bb", "\u5230");
        }
        if (matchesAny(lower, "email", "\u90ae\u4ef6", "inbox",
                "\u6536\u4ef6\u7bb1", "mail")) {
            return planEmail(userText);
        }
        if (matchesAny(lower, "news", "headlines", "latest news", "what's new",
                "\u65b0\u95fb", "\u5934\u6761", "\u79d1\u6280\u65b0\u95fb",
                "\u8d22\u7ecf\u65b0\u95fb", "\u653f\u6cbb\u65b0\u95fb")) {
            return planNews(userText);
        }

        return new Plan(Collections.emptyList(), HELP_TEXT);
    }

    // ── plan builders (each returns function-call steps) ────────────────

    private Plan planCall(String userText) {
        String phone = extractPhone(userText);
        if (phone != null) {
            return new Plan(
                    stepsOf(step("phone.dial", argsOf("phone", phone),
                            RiskLevel.LOW, "phone.dial(phone=\"" + phone + "\")")),
                    "I'll open the dialer for you:");
        }
        String name = extractAfterKeywords(userText,
                "call", "dial", "\u62e8\u6253", "\u6253\u7535\u8bdd\u7ed9", "\u6253\u7535\u8bdd");
        if (name != null && !name.isEmpty()) {
            return new Plan(
                    stepsOf(step("contacts.lookup", argsOf("name", name),
                            RiskLevel.MEDIUM, "contacts.lookup(name=\"" + name + "\")")),
                    "I'll look up the contact for you:");
        }
        return new Plan(Collections.emptyList(),
                "I'd like to make a call for you. Please provide a phone number or contact name.");
    }

    private Plan planSms(String userText) {
        String phone = extractPhone(userText);
        String body = extractSmsBody(userText);
        if (phone != null) {
            return new Plan(
                    stepsOf(step("sms.compose", argsOf("phone", phone, "body", body),
                            RiskLevel.MEDIUM,
                            "sms.compose(phone=\"" + phone + "\", body=\"" + body + "\")")),
                    "I'll compose a text message:");
        }
        String name = extractSmsRecipientName(userText);
        if (name != null && !name.isEmpty()) {
            List<ActionSpec> actions = new ArrayList<>();
            actions.add(step("contacts.lookup", argsOf("name", name),
                    RiskLevel.MEDIUM, "contacts.lookup(name=\"" + name + "\")"));
            actions.add(step("sms.compose", argsOf("phone", "[from_lookup]", "body", body),
                    RiskLevel.MEDIUM,
                    "sms.compose(phone=[from_lookup], body=\"" + body + "\")"));
            return new Plan(actions, "I'll look up the contact, then compose an SMS:");
        }
        return new Plan(Collections.emptyList(),
                "I'd like to send a text for you. Please provide a phone number or contact name.");
    }

    private Plan planNavigation(String userText, String... keywords) {
        String dest = extractAfterKeywords(userText, keywords);
        if (dest != null && !dest.isEmpty()) {
            return new Plan(
                    stepsOf(step("maps.navigate", argsOf("destination", dest),
                            RiskLevel.LOW,
                            "maps.navigate(destination=\"" + dest + "\")")),
                    "I'll start navigation:");
        }
        return new Plan(Collections.emptyList(),
                "Where would you like to navigate? Please provide a destination.");
    }

    private Plan planEmail(String userText) {
        String lower = userText.toLowerCase();
        String mode = matchesAny(lower, "list", "today", "received today",
                "\u5217\u8868", "\u4eca\u5929", "\u4eca\u65e5")
                ? "today_list"
                : "summary";
        return new Plan(
                stepsOf(step("email.summary", argsOf("mode", mode),
                        RiskLevel.LOW, "email.summary(mode=\"" + mode + "\")")),
                "I'll check Gmail email notifications:");
    }

    private Plan planNews(String userText) {
        String lower = userText.toLowerCase();
        String category;
        if (matchesAny(lower, "ai", "artificial intelligence",
                "\u4eba\u5de5\u667a\u80fd", "\u673a\u5668\u5b66\u4e60")) {
            category = "ai";
        } else if (matchesAny(lower, "crypto", "bitcoin", "ethereum", "blockchain",
                "\u52a0\u5bc6\u8d27\u5e01", "\u6bd4\u7279\u5e01")) {
            category = "crypto";
        } else if (matchesAny(lower, "finance", "market", "stock", "wall street",
                "\u91d1\u878d", "\u80a1\u5e02", "\u534e\u5c14\u8857")) {
            category = "finance";
        } else if (matchesAny(lower, "politics", "political", "government",
                "\u653f\u6cbb", "\u653f\u5e9c")) {
            category = "politics";
        } else if (matchesAny(lower, "science", "research", "discovery",
                "\u79d1\u5b66", "\u7814\u7a76")) {
            category = "science";
        } else if (matchesAny(lower, "security", "cyber", "hack", "vulnerability",
                "\u5b89\u5168", "\u7f51\u7edc\u5b89\u5168")) {
            category = "security";
        } else if (matchesAny(lower, "startup", "startups", "venture", "funding",
                "\u521b\u4e1a", "\u878d\u8d44")) {
            category = "startups";
        } else if (matchesAny(lower, "energy", "oil", "opec", "nuclear",
                "\u80fd\u6e90", "\u77f3\u6cb9", "\u6838\u80fd")) {
            category = "energy";
        } else if (matchesAny(lower, "asia", "china", "japan", "korea", "india",
                "\u4e9a\u6d32", "\u4e2d\u56fd", "\u65e5\u672c", "\u97e9\u56fd")) {
            category = "asia";
        } else if (matchesAny(lower, "middle east", "middleeast", "israel", "iran",
                "gaza", "arab", "\u4e2d\u4e1c")) {
            category = "middleeast";
        } else if (matchesAny(lower, "tech", "technology", "software", "hardware",
                "\u79d1\u6280", "\u6280\u672f")) {
            category = "tech";
        } else {
            // No specific category detected — return top world headlines
            category = "general";
        }
        return new Plan(
                stepsOf(step("news.fetch", argsOf("category", category),
                        RiskLevel.LOW, "news.fetch(category=\"" + category + "\")")),
                "Here are the latest " + category + " headlines:");
    }

    private Plan planSpotify(String userText) {
        String lower = userText.toLowerCase();
        String action = "search";
        if (matchesAny(lower, "pause", "stop", "\u6682\u505c", "\u505c\u6b62")) {
            action = "pause";
        } else if (matchesAny(lower, "next", "\u4e0b\u4e00\u9996")) {
            action = "next";
        } else if (matchesAny(lower, "previous", "last song", "\u4e0a\u4e00\u9996")) {
            action = "previous";
        } else if (matchesAny(lower, "play", "\u64ad\u653e")) {
            action = "play";
        } else if (matchesAny(lower, "search", "find", "\u641c\u7d22", "\u68c0\u7d22")) {
            action = "search";
        }

        String query = extractAfterKeywords(userText,
                "play", "search spotify for", "search", "find", "spotify",
                "\u64ad\u653e", "\u641c\u7d22", "\u68c0\u7d22");
        if (query != null) {
            query = query
                    .replaceFirst("(?i)^(music|song|for|on spotify|in spotify)\\s+", "")
                    .replaceFirst("(?i)\\s+(on|in)\\s+spotify$", "")
                    .replaceFirst("(?i)\\s+spotify$", "")
                    .trim();
        }

        Map<String, String> args = new HashMap<>();
        args.put("action", action);
        String spotifyUri = extractSpotifyUri(userText);
        if (spotifyUri != null) {
            args.put("uri", spotifyUri);
        } else if (query != null && !query.isEmpty()
                && !matchesAny(action, "pause", "next", "previous")) {
            args.put("query", query);
        }

        return new Plan(
                stepsOf(step("spotify.control", args, RiskLevel.MEDIUM,
                        "spotify.control(action=\"" + action + "\""
                                + (args.containsKey("query") ? ", query=\"" + args.get("query") + "\"" : "")
                                + (args.containsKey("uri") ? ", uri=\"" + args.get("uri") + "\"" : "")
                                + ")")),
                "I'll control Spotify:");
    }

    private Plan planWeather(String userText) {
        String location = extractWeatherLocation(userText);

        Map<String, String> args = new HashMap<>();
        args.put("location", location);
        return new Plan(
                stepsOf(step("weather.lookup", args, RiskLevel.LOW,
                        "weather.lookup(location=\"" + args.get("location") + "\")")),
                "I'll check the weather:");
    }

    private Plan planMediaShare(String userText) {
        String lower = userText.toLowerCase();
        String fileType = "photo";
        if (matchesAny(lower, "video", "\u89c6\u9891")) {
            fileType = "video";
        } else if (matchesAny(lower, "file", "document", "\u6587\u4ef6")) {
            fileType = "any";
        }

        String recipient = extractAfterKeywords(userText,
                "send to", "share with", "to", "\u53d1\u7ed9", "\u7ed9");
        if (recipient != null) {
            recipient = recipient
                    .replaceFirst("(?i)^(my |the |latest |recent |photo|picture|image|video|file)\\s+", "")
                    .trim();
        }

        List<ActionSpec> actions = new ArrayList<>();
        if (recipient != null && !recipient.isEmpty()) {
            actions.add(step("contacts.lookup", argsOf("name", recipient),
                    RiskLevel.MEDIUM, "contacts.lookup(name=\"" + recipient + "\")"));
            actions.add(step("media.share",
                    argsOf("file_type", fileType, "recency", "latest",
                            "contact_name", recipient, "phone", "[from_lookup]"),
                    RiskLevel.HIGH,
                    "media.share(file_type=\"" + fileType + "\", phone=[from_lookup])"));
            return new Plan(actions, "I'll find the recipient, then open sharing for the latest " + fileType + ":");
        }

        actions.add(step("media.share",
                argsOf("file_type", fileType, "recency", "latest"),
                RiskLevel.HIGH,
                "media.share(file_type=\"" + fileType + "\")"));
        return new Plan(actions, "I'll find the latest " + fileType + " and open sharing:");
    }

    private Plan planAppCapability(String userText) {
        return new Plan(
                stepsOf(step("apps.capability", argsOf("command", userText),
                        RiskLevel.LOW, "apps.capability(command=\"" + userText + "\")")),
                "I'll check the installed apps and available capabilities:");
    }

    // ── text parsing helpers ────────────────────────────────────────────

    private boolean matchesAny(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }

    private boolean isSpotifyRequest(String lower) {
        return matchesAny(lower, "spotify", "music", "song", "playlist",
                "\u97f3\u4e50", "\u6b4c\u66f2", "\u64ad\u653e", "\u6682\u505c\u64ad\u653e");
    }

    private boolean isMediaShareRequest(String lower) {
        return matchesAny(lower, "latest photo", "recent photo", "latest picture",
                "recent picture", "latest image", "recent image", "latest video",
                "recent video", "send my photo", "share my photo",
                "\u6700\u8fd1\u62cd\u6444", "\u6700\u8fd1\u7684\u7167\u7247",
                "\u7167\u7247\u53d1\u7ed9", "\u53d1\u7ed9")
                && matchesAny(lower, "photo", "picture", "image", "video", "file",
                "\u7167\u7247", "\u56fe\u7247", "\u89c6\u9891", "\u6587\u4ef6");
    }

    private boolean isAppCapabilityRequest(String lower) {
        return matchesAny(lower, "installed apps", "list apps", "what apps",
                "can this phone", "can you execute", "can i run",
                "\u624b\u673a\u91cc\u6709", "\u5df2\u5b89\u88c5", "\u6709\u54ea\u4e9bapp",
                "\u53ef\u4ee5\u6267\u884c", "\u80fd\u5426\u6267\u884c");
    }

    private String extractPhone(String text) {
        Matcher m = PHONE_PATTERN.matcher(text);
        if (m.find()) return m.group(1).replaceAll("[\\s()]", "");
        return null;
    }

    private String extractSpotifyUri(String text) {
        Matcher m = SPOTIFY_URI_PATTERN.matcher(text);
        if (m.find()) return m.group(1);
        return null;
    }

    private String extractWeatherLocation(String text) {
        String location = extractAfterKeywords(text,
                "weather in", "weather for", "forecast in", "forecast for",
                "weather at", "weather near", "in", "for", "at", "near",
                "\u5929\u6c14\u5728", "\u5929\u6c14");
        if (location == null) {
            location = text;
        }

        location = location
                .replaceAll("(?i)\\b(what|how|is|the|weather|forecast|like|today|now|currently|outside|please|tell|me|about)\\b", " ")
                .replaceAll("(?i)\\b(what's|hows|how's)\\b", " ")
                .replace("\u4eca\u5929", " ")
                .replace("\u4eca\u65e5", " ")
                .replace("\u73b0\u5728", " ")
                .replace("\u5929\u6c14", " ")
                .replace("\u5982\u4f55", " ")
                .replace("\u600e\u4e48\u6837", " ")
                .replaceAll("[?？,，。.!！]", " ")
                .replaceAll("\\s+", " ")
                .trim();

        if (location.matches("(?i)^(today|now|current|currently)$")) {
            return "";
        }
        return location;
    }

    private String extractAfterKeywords(String text, String... keywords) {
        String lower = text.toLowerCase();
        for (String kw : keywords) {
            int idx = lower.indexOf(kw.toLowerCase());
            if (idx >= 0) {
                String after = text.substring(idx + kw.length()).trim();
                after = after.replaceFirst("^(to |for |\u7ed9)", "").trim();
                if (!after.isEmpty()) return after;
            }
        }
        return null;
    }

    private String extractSmsBody(String text) {
        Pattern sayingPattern = Pattern.compile(
                "(?:saying|say|with message|:)\\s+(.+)", Pattern.CASE_INSENSITIVE);
        Matcher m = sayingPattern.matcher(text);
        if (m.find()) return m.group(1).trim();

        Pattern quotePattern = Pattern.compile("[\"'](.+?)[\"']");
        m = quotePattern.matcher(text);
        if (m.find()) return m.group(1);

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
                "\u53d1\u77ed\u4fe1\u7ed9", "\u53d1\u77ed\u4fe1", "\u77ed\u4fe1"};
        for (String kw : keywords) {
            int idx = lower.indexOf(kw);
            if (idx >= 0) {
                String after = text.substring(idx + kw.length()).trim();
                after = after.replaceFirst("^(to |\u7ed9)", "").trim();
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

    // ── builder helpers ─────────────────────────────────────────────────

    private ActionSpec step(String tool, Map<String, String> args,
                            RiskLevel risk, String humanDescription) {
        return new ActionSpec(tool, args, risk, humanDescription);
    }

    private Map<String, String> argsOf(String... keyValues) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < keyValues.length - 1; i += 2) {
            m.put(keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    private List<ActionSpec> stepsOf(ActionSpec... specs) {
        List<ActionSpec> list = new ArrayList<>();
        Collections.addAll(list, specs);
        return list;
    }
}
