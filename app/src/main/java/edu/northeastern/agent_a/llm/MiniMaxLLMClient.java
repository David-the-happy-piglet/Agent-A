package edu.northeastern.agent_a.llm;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import edu.northeastern.agent_a.BuildConfig;
import edu.northeastern.agent_a.core.tools.ActionSpec;
import edu.northeastern.agent_a.core.tools.Plan;
import edu.northeastern.agent_a.core.tools.RiskLevel;
import edu.northeastern.agent_a.core.tools.Tool;
import edu.northeastern.agent_a.core.tools.ToolRegistry;

public class MiniMaxLLMClient implements LLMClient {

    private static final String TAG = "MiniMaxLLMClient";
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final String HELP_MESSAGE =
            "I can help you with calls, text messages, navigation, and email summaries. "
                    + "Try: \"Call Mom\", \"Text John saying meet at 5\", "
                    + "\"Navigate to Boston\", or \"Show my emails\".";
    private static final String JSON_OUTPUT_REMINDER =
            "\n\nReturn only one JSON object in this exact shape: "
                    + "{\"message\":\"...\",\"steps\":[{\"tool\":\"...\",\"args\":{\"key\":\"value\"}}]}. "
                    + "If the request is not an actionable phone task, return "
                    + "{\"message\":\"" + HELP_MESSAGE.replace("\"", "\\\"") + "\",\"steps\":[]}. "
                    + "Do not add markdown fences, examples, or explanation.";

    private final ToolRegistry registry;
    private final String apiKey;
    private final String baseUrl;
    private final String model;

    public MiniMaxLLMClient(ToolRegistry registry) {
        this(registry, BuildConfig.MINIMAX_API_KEY, BuildConfig.MINIMAX_BASE_URL, BuildConfig.MINIMAX_MODEL);
    }

    public MiniMaxLLMClient(ToolRegistry registry, String apiKey, String baseUrl, String model) {
        this.registry = registry;
        this.apiKey = apiKey != null ? apiKey.trim() : "";
        this.baseUrl = (baseUrl == null || baseUrl.trim().isEmpty())
                ? "https://api.minimax.io/v1/text/chatcompletion_v2"
                : baseUrl.trim();
        this.model = (model == null || model.trim().isEmpty())
                ? "M2-her"
                : model.trim();
    }

    @Override
    public Plan call(LLMRequest request) {
        if (apiKey.isEmpty()) {
            throw new IllegalStateException("MiniMax API key is missing. Add MINIMAX_API_KEY to local.properties.");
        }

        HttpURLConnection connection = null;
        try {
            URL url = new URL(baseUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            connection.setRequestProperty("Content-Type", "application/json");

            byte[] body = buildRequestBody(request).getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = connection.getOutputStream()) {
                os.write(body);
            }

            int statusCode = connection.getResponseCode();
            String responseText = readFully(statusCode >= 400
                    ? connection.getErrorStream()
                    : connection.getInputStream());

            if (statusCode >= 400) {
                throw new IllegalStateException(parseErrorMessage(responseText, statusCode));
            }

            return parsePlan(responseText);
        } catch (Exception e) {
            Log.e(TAG, "MiniMax request failed", e);
            throw new IllegalStateException("MiniMax request failed: " + e.getMessage(), e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String buildRequestBody(LLMRequest request) throws JSONException {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("temperature", 0);

        JSONArray messages = new JSONArray();
        messages.put(new JSONObject()
                .put("role", "system")
                .put("name", "MiniMax AI")
                .put("content", request.getSystemPrompt() + JSON_OUTPUT_REMINDER));
        messages.put(new JSONObject()
                .put("role", "user")
                .put("name", "User")
                .put("content", request.getUserQuery() + JSON_OUTPUT_REMINDER));
        body.put("messages", messages);
        return body.toString();
    }

    private Plan parsePlan(String responseText) throws JSONException {
        JSONObject response = new JSONObject(responseText);
        JSONArray choices = response.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            throw new IllegalStateException("MiniMax returned no choices.");
        }

        JSONObject firstChoice = choices.getJSONObject(0);
        JSONObject message = firstChoice.optJSONObject("message");
        if (message == null) {
            throw new IllegalStateException("MiniMax returned no message content.");
        }

        String content = message.optString("content", "").trim();
        if (content.isEmpty()) {
            throw new IllegalStateException("MiniMax returned empty message content.");
        }

        JSONObject planJson;
        try {
            planJson = parseStructuredContent(content);
        } catch (JSONException e) {
            Log.w(TAG, "MiniMax returned non-JSON content, falling back to help message: " + content);
            return new Plan(Collections.emptyList(), HELP_MESSAGE);
        }

        String assistantMessage = planJson.optString("message", "");
        JSONArray stepsJson = planJson.optJSONArray("steps");
        if (stepsJson == null) {
            stepsJson = new JSONArray();
        }

        List<ActionSpec> steps = new ArrayList<>();
        for (int i = 0; i < stepsJson.length(); i++) {
            JSONObject step = stepsJson.getJSONObject(i);
            String toolName = step.optString("tool", "").trim();
            if (toolName.isEmpty() || !registry.has(toolName)) {
                continue;
            }

            Map<String, String> args = new HashMap<>();
            JSONObject argsObject = step.optJSONObject("args");
            if (argsObject != null) {
                Iterator<String> keys = argsObject.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    args.put(key, String.valueOf(argsObject.opt(key)));
                }
            }

            steps.add(new ActionSpec(
                    toolName,
                    args,
                    riskFor(toolName),
                    humanDescription(toolName, args)));
        }

        if (assistantMessage.isEmpty()) {
            assistantMessage = steps.isEmpty()
                    ? HELP_MESSAGE
                    : "Here's the plan:";
        }

        if (steps.isEmpty()) {
            assistantMessage = HELP_MESSAGE;
        }

        return new Plan(steps, assistantMessage);
    }

    private JSONObject parseStructuredContent(String content) throws JSONException {
        String trimmed = content.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed
                    .replaceFirst("^```(?:json)?\\s*", "")
                    .replaceFirst("\\s*```$", "")
                    .trim();
        }

        try {
            return new JSONObject(trimmed);
        } catch (JSONException objectError) {
            try {
                JSONArray array = new JSONArray(trimmed);
                return new JSONObject().put("message", "Here's the plan:").put("steps", array);
            } catch (JSONException arrayError) {
                int firstObject = trimmed.indexOf('{');
                int lastObject = trimmed.lastIndexOf('}');
                if (firstObject >= 0 && lastObject > firstObject) {
                    return new JSONObject(trimmed.substring(firstObject, lastObject + 1));
                }

                int firstArray = trimmed.indexOf('[');
                int lastArray = trimmed.lastIndexOf(']');
                if (firstArray >= 0 && lastArray > firstArray) {
                    JSONArray array = new JSONArray(trimmed.substring(firstArray, lastArray + 1));
                    return new JSONObject().put("message", "Here's the plan:").put("steps", array);
                }

                throw objectError;
            }
        }
    }

    private RiskLevel riskFor(String toolName) {
        Tool tool = registry.get(toolName);
        return tool != null ? tool.defaultRiskLevel() : RiskLevel.LOW;
    }

    private String humanDescription(String toolName, Map<String, String> args) {
        if (args.isEmpty()) {
            return toolName + "()";
        }

        List<String> keys = new ArrayList<>(args.keySet());
        Collections.sort(keys);
        StringBuilder sb = new StringBuilder(toolName).append("(");
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            String key = keys.get(i);
            sb.append(key).append("=\"").append(args.get(key)).append("\"");
        }
        sb.append(")");
        return sb.toString();
    }

    private String parseErrorMessage(String responseText, int statusCode) {
        try {
            JSONObject root = new JSONObject(responseText);
            JSONObject error = root.optJSONObject("error");
            if (error != null) {
                String message = error.optString("message");
                if (!message.isEmpty()) {
                    return "MiniMax API error (" + statusCode + "): " + message;
                }
            }
        } catch (JSONException ignored) {
            // Fall back to raw text below.
        }

        return "MiniMax API error (" + statusCode + "): " + responseText;
    }

    private String readFully(InputStream inputStream) throws Exception {
        if (inputStream == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }
}
