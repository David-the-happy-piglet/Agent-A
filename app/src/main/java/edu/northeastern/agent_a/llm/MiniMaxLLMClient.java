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

/**
 * MiniMaxLLMClient sends the prompt to the MiniMax API and parses the response into a Plan.
 *
 * Key differences from GeminiLLMClient:
 *   - Uses HttpURLConnection instead of OkHttp
 *   - Auth uses "Authorization: Bearer key" header instead of x-goog-api-key
 *   - JSON output is guided through a text reminder added to the prompt
 *   - Filters out tool names not in the registry to guard against model hallucination
 */
public class MiniMaxLLMClient implements LLMClient {

    private static final String TAG = "MiniMaxLLMClient";
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS    = 30000;

    // Added to both system and user messages to guide the model's output format
    private static final String JSON_OUTPUT_REMINDER =
            "\n\nReturn only one JSON object in this exact shape: "
                    + "{\"message\":\"...\",\"steps\":[{\"tool\":\"...\",\"args\":{\"key\":\"value\"}}]}. "
                    + "Use tool steps whenever the request matches an available tool, including news.fetch for news requests. "
                    + "Never invent tool names that are not listed. "
                    + "If no tool is needed, return a normal conversational reply in \"message\" and use an empty steps array. "
                    + "Do not add markdown fences, examples, or explanation.";

    private final ToolRegistry registry;
    private final String apiKey;
    private final String baseUrl;
    private final String model;

    // ── Constructors ──────────────────────────────────────────────────────

    /**
     * Constructor used when credentials come from BuildConfig (local.properties).
     *
     * @param registry used to validate tool names and look up risk levels
     */
    public MiniMaxLLMClient(ToolRegistry registry) {
        this(registry, BuildConfig.MINIMAX_API_KEY, BuildConfig.MINIMAX_BASE_URL, BuildConfig.MINIMAX_MODEL);
    }

    /**
     * Constructor used when credentials are passed directly (e.g. hardcoded in Activity).
     *
     * @param registry used to validate tool names and look up risk levels
     * @param apiKey   the MiniMax API key
     * @param baseUrl  the API endpoint URL; defaults to the standard MiniMax URL if empty
     * @param model    the model name; defaults to "M2-her" if empty
     */
    public MiniMaxLLMClient(ToolRegistry registry, String apiKey, String baseUrl, String model) {
        this.registry = registry;
        this.apiKey   = apiKey != null ? apiKey.trim() : "";
        this.baseUrl  = (baseUrl == null || baseUrl.trim().isEmpty())
                ? "https://api.minimax.io/v1/text/chatcompletion_v2"
                : baseUrl.trim();
        this.model    = (model == null || model.trim().isEmpty()) ? "M2-her" : model.trim();
    }

    // ── HTTP request ──────────────────────────────────────────────────────

    /**
     * Sends the request to MiniMax using HttpURLConnection.
     * On HTTP error (4xx/5xx), reads the error stream and throws with a clear message.
     * Connect and read timeouts prevent the call from hanging on a slow network.
     *
     * @param request contains the system prompt and the user query
     * @return a Plan with actions, or throws IllegalStateException on failure
     */
    @Override
    public Plan call(LLMRequest request) {
        if (apiKey.isEmpty()) {
            throw new IllegalStateException("MiniMax API key is missing.");
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
            if (connection != null) connection.disconnect();
        }
    }

    // ── Request building ──────────────────────────────────────────────────

    /**
     * Builds the JSON request body.
     * JSON_OUTPUT_REMINDER is appended to both messages to guide the model
     * to always return the expected JSON format.
     *
     * @param request the prompt containing system instructions and user query
     * @return a JSON string ready to send as the request body
     */
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

    // ── Response parsing ──────────────────────────────────────────────────

    /**
     * Parses the MiniMax response into a Plan.
     * Filters out any tool names not found in the registry (hallucination guard).
     * Falls back to a plain-text Plan if the response is not valid JSON.
     *
     * @param responseText the raw JSON string from MiniMax
     * @return a Plan with the parsed actions
     */
    private Plan parsePlan(String responseText) throws JSONException {
        JSONObject response = new JSONObject(responseText);
        JSONArray choices = response.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            throw new IllegalStateException("MiniMax returned no choices.");
        }

        JSONObject message = choices.getJSONObject(0).optJSONObject("message");
        if (message == null) throw new IllegalStateException("MiniMax returned no message.");

        String content = message.optString("content", "").trim();
        if (content.isEmpty()) throw new IllegalStateException("MiniMax returned empty content.");

        JSONObject planJson;
        try {
            planJson = parseStructuredContent(content);
        } catch (JSONException e) {
            Log.w(TAG, "Non-JSON content, treating as plain text: " + content);
            return new Plan(Collections.emptyList(), content);
        }

        String assistantMessage = planJson.optString("message", "");
        int originalStepCount = 0;
        JSONArray stepsJson = planJson.optJSONArray("steps");
        if (stepsJson == null) {
            stepsJson = new JSONArray();
        } else {
            originalStepCount = stepsJson.length();
        }

        List<ActionSpec> steps = new ArrayList<>();
        for (int i = 0; i < stepsJson.length(); i++) {
            JSONObject step = stepsJson.getJSONObject(i);
            String toolName = step.optString("tool", "").trim();
            if (toolName.isEmpty() || !registry.has(toolName)) {
                Log.w(TAG, "Ignoring unknown tool name: " + toolName);
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

            steps.add(new ActionSpec(toolName, args, riskFor(toolName),
                    humanDescription(toolName, args)));
        }

        if (assistantMessage.isEmpty()) {
            assistantMessage = steps.isEmpty() ? content : "Here's the plan:";
        }
        if (steps.isEmpty() && originalStepCount > 0 && assistantMessage.equals(content)) {
            assistantMessage = "I can't perform that action in this app yet, but I can still chat about it.";
        }

        return new Plan(steps, assistantMessage);
    }

    /**
     * Extracts a valid JSON object from raw model text.
     * Handles markdown code fences, bare arrays, and extra text around the JSON.
     *
     * @param content the raw text returned by the model
     * @return a JSONObject containing "message" and "steps" fields
     */
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
                int lastObject  = trimmed.lastIndexOf('}');
                if (firstObject >= 0 && lastObject > firstObject) {
                    return new JSONObject(trimmed.substring(firstObject, lastObject + 1));
                }
                int firstArray = trimmed.indexOf('[');
                int lastArray  = trimmed.lastIndexOf(']');
                if (firstArray >= 0 && lastArray > firstArray) {
                    JSONArray array = new JSONArray(trimmed.substring(firstArray, lastArray + 1));
                    return new JSONObject().put("message", "Here's the plan:").put("steps", array);
                }
                throw objectError;
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Looks up the tool's default risk level from the registry.
     *
     * @param toolName the tool name as returned by the model
     * @return the tool's RiskLevel, or LOW if the tool is not found
     */
    private RiskLevel riskFor(String toolName) {
        Tool tool = registry.get(toolName);
        return tool != null ? tool.defaultRiskLevel() : RiskLevel.LOW;
    }

    /**
     * Builds a readable description string, e.g. sms.compose(body="hi", phone="123").
     *
     * @param toolName the tool name
     * @param args     the arguments map
     * @return a human-readable string shown in the ActionPreviewHelper dialog
     */
    private String humanDescription(String toolName, Map<String, String> args) {
        if (args.isEmpty()) return toolName + "()";
        List<String> keys = new ArrayList<>(args.keySet());
        Collections.sort(keys);
        StringBuilder sb = new StringBuilder(toolName).append("(");
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) sb.append(", ");
            String key = keys.get(i);
            sb.append(key).append("=\"").append(args.get(key)).append("\"");
        }
        return sb.append(")").toString();
    }

    private String parseErrorMessage(String responseText, int statusCode) {
        try {
            JSONObject root = new JSONObject(responseText);
            JSONObject error = root.optJSONObject("error");
            if (error != null) {
                String msg = error.optString("message");
                if (!msg.isEmpty()) return "MiniMax API error (" + statusCode + "): " + msg;
            }
        } catch (JSONException ignored) {}
        return "MiniMax API error (" + statusCode + "): " + responseText;
    }

    private String readFully(InputStream inputStream) throws Exception {
        if (inputStream == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }
}