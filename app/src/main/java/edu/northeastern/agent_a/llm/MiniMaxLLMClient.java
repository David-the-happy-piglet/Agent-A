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
                throw new IllegalStateException("API Error " + statusCode + ": " + responseText);
            }

            return parsePlan(responseText);
        } catch (Exception e) {
            Log.e(TAG, "MiniMax request failed", e);
            return new Plan(Collections.emptyList(), "I'm having trouble connecting to my brain. Error: " + e.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String buildRequestBody(LLMRequest request) throws JSONException {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("temperature", 0.01);

        JSONArray messages = new JSONArray();
        messages.put(new JSONObject()
                .put("role", "system")
                .put("name", "Agent-A")
                .put("content", request.getSystemPrompt()));
        
        messages.put(new JSONObject()
                .put("role", "user")
                .put("name", "User")
                .put("content", request.getUserQuery()));
                
        body.put("messages", messages);
        return body.toString();
    }

    private Plan parsePlan(String responseText) throws JSONException {
        JSONObject response = new JSONObject(responseText);
        JSONArray choices = response.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            return new Plan(Collections.emptyList(), "MiniMax returned no response choices.");
        }

        String content = choices.getJSONObject(0).getJSONObject("message").optString("content", "").trim();
        Log.d(TAG, "Raw Content: " + content);

        if (content.isEmpty()) {
            return new Plan(Collections.emptyList(), "I'm sorry, I couldn't generate a plan for that request.");
        }

        JSONArray stepsJson = null;
        String assistantMessage = "Planning complete.";
        
        try {
            String cleaned = content;
            if (content.contains("[")) {
                cleaned = content.substring(content.indexOf("["), content.lastIndexOf("]") + 1);
            }
            stepsJson = new JSONArray(cleaned);
        } catch (Exception e) {
            // Fallback: Check if it's the old object format or just text
            try {
                JSONObject obj = new JSONObject(content);
                assistantMessage = obj.optString("message", "Processing...");
                stepsJson = obj.optJSONArray("steps");
            } catch (Exception e2) {
                Log.w(TAG, "Content is not JSON, returning as message");
                return new Plan(Collections.emptyList(), content);
            }
        }

        List<ActionSpec> actions = new ArrayList<>();
        if (stepsJson != null) {
            for (int i = 0; i < stepsJson.length(); i++) {
                JSONObject stepObj = stepsJson.getJSONObject(i);
                String toolName = stepObj.optString("tool", "");
                
                if (registry.has(toolName)) {
                    Map<String, String> argsMap = new HashMap<>();
                    // Support both "parameters" (new) and "args" (old) keys
                    JSONObject argsObj = stepObj.optJSONObject("parameters");
                    if (argsObj == null) argsObj = stepObj.optJSONObject("args");
                    
                    if (argsObj != null) {
                        Iterator<String> keys = argsObj.keys();
                        while (keys.hasNext()) {
                            String k = keys.next();
                            argsMap.put(k, String.valueOf(argsObj.opt(k)));
                        }
                    }
                    
                    int stepNum = stepObj.optInt("step", i + 1);
                    String label = stepObj.optString("label", toolName);
                    
                    actions.add(new ActionSpec(toolName, argsMap, RiskLevel.MEDIUM, label, stepNum, label));
                }
            }
        }

        return new Plan(actions, assistantMessage);
    }

    private String readFully(InputStream is) throws Exception {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }
}
