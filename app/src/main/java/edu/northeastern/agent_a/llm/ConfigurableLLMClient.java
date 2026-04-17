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

import edu.northeastern.agent_a.core.tools.ActionSpec;
import edu.northeastern.agent_a.core.tools.Plan;
import edu.northeastern.agent_a.core.tools.RiskLevel;
import edu.northeastern.agent_a.core.tools.Tool;
import edu.northeastern.agent_a.core.tools.ToolRegistry;

/**
 * ConfigurableLLMClient works with any OpenAI-compatible chat completions API.
 * The base URL, model name, and API key are all provided at runtime by the caller.
 * This makes it easy to add a new LLM backend without changing this class.
 *
 * The structure is almost the same as MiniMaxLLMClient.
 * The key difference: no hardcoded URL or model — everything comes from the constructor.
 */
public class ConfigurableLLMClient implements LLMClient {

    private static final String TAG = "ConfigurableLLMClient";
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS    = 30000;

    // Same JSON reminder as MiniMax to guide the model to return the expected format
    private static final String JSON_OUTPUT_REMINDER =
            "\n\nReturn only one JSON object in this exact shape: "
                    + "{\"message\":\"...\",\"steps\":[{\"tool\":\"...\",\"args\":{\"key\":\"value\"}}]}. "
                    + "Use tool steps whenever the request matches an available tool. "
                    + "Never invent tool names that are not listed. "
                    + "If no tool is needed, return a normal conversational reply in \"message\" and use an empty steps array. "
                    + "Do not add markdown fences, examples, or explanation.";

    private final ToolRegistry registry;
    private final String apiKey;
    private final String baseUrl;
    private final String model;

    // ── Constructor ───────────────────────────────────────────────────────

    /**
     * All config is passed in — nothing is hardcoded in this class.
     *
     * @param registry used to validate tool names returned by the model
     * @param apiKey   the API key for the chosen LLM service
     * @param baseUrl  the full endpoint URL, e.g. "https://api.openai.com/v1/chat/completions"
     * @param model    the model name, e.g. "gpt-4o"
     */
    public ConfigurableLLMClient(ToolRegistry registry, String apiKey, String baseUrl, String model) {
        this.registry = registry;
        this.apiKey   = apiKey   != null ? apiKey.trim()   : "";
        this.baseUrl  = baseUrl  != null ? baseUrl.trim()  : "";
        this.model    = model    != null ? model.trim()    : "";
    }

    // ── HTTP request ──────────────────────────────────────────────────────

    /**
     * Sends the request to the configured API endpoint.
     * Fails fast if any required config value is missing.
     * Uses the same POST + Bearer token pattern as MiniMaxLLMClient.
     *
     * @param request contains the system prompt and the user query
     * @return a Plan with actions, or throws IllegalStateException on failure
     */
    @Override
    public Plan call(LLMRequest request) {
        if (apiKey.isEmpty() || baseUrl.isEmpty() || model.isEmpty()) {
            throw new IllegalStateException("Custom LLM config is incomplete.");
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
            Log.e(TAG, "Custom LLM request failed", e);
            throw new IllegalStateException("Custom LLM request failed: " + e.getMessage(), e);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    // ── Request building ──────────────────────────────────────────────────

    /**
     * Builds the JSON request body in OpenAI chat completions format.
     * JSON_OUTPUT_REMINDER is appended to both messages to guide the model.
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
                .put("content", request.getSystemPrompt() + JSON_OUTPUT_REMINDER));
        messages.put(new JSONObject()
                .put("role", "user")
                .put("content", request.getUserQuery() + JSON_OUTPUT_REMINDER));
        body.put("messages", messages);
        return body.toString();
    }

    // ── Response parsing ──────────────────────────────────────────────────

    /**
     * Parses the API response into a Plan using the same logic as MiniMaxLLMClient.
     * Reads choices[0].message.content, parses JSON, and filters unknown tool names.
     *
     * @param responseText the raw JSON string from the API
     * @return a Plan with the parsed actions
     */
    private Plan parsePlan(String responseText) throws JSONException {
        JSONObject response = new JSONObject(responseText);
        JSONArray choices = response.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            throw new IllegalStateException("Custom LLM returned no choices.");
        }

        JSONObject message = choices.getJSONObject(0).optJSONObject("message");
        if (message == null) throw new IllegalStateException("Custom LLM returned no message.");

        String content = message.optString("content", "").trim();
        if (content.isEmpty()) throw new IllegalStateException("Custom LLM returned empty content.");

        JSONObject planJson;
        try {
            planJson = parseStructuredContent(content);
        } catch (JSONException e) {
            Log.w(TAG, "Non-JSON content: " + content);
            return new Plan(Collections.emptyList(), content);
        }

        String assistantMessage = planJson.optString("message", "");
        JSONArray stepsJson = planJson.optJSONArray("steps");
        if (stepsJson == null) stepsJson = new JSONArray();

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
     * Builds a readable description string, e.g. maps.navigate(destination="Boston").
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
                if (!msg.isEmpty()) return "API error (" + statusCode + "): " + msg;
            }
        } catch (JSONException ignored) {}
        return "API error (" + statusCode + "): " + responseText;
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