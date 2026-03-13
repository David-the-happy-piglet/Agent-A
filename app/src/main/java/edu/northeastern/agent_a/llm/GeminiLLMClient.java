package edu.northeastern.agent_a.llm;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import edu.northeastern.agent_a.core.tools.ActionSpec;
import edu.northeastern.agent_a.core.tools.Plan;
import edu.northeastern.agent_a.core.tools.RiskLevel;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class GeminiLLMClient implements LLMClient {

    private static final String TAG = "GeminiLLMClient";
    private static final String API_URL =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent";
    private static final MediaType JSON = MediaType.get("application/json");

    private final String apiKey;
    private final OkHttpClient http = new OkHttpClient();

    public GeminiLLMClient(String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    public Plan call(LLMRequest request) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                Plan result = callOnce(request, attempt);
                if (result != null) return result;
                // null means 429 — wait and retry
                Thread.sleep(2000L * attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return errorPlan("Request interrupted.");
            } catch (Exception e) {
                Log.e(TAG, "Attempt " + attempt + " failed", e);
                return errorPlan("Request failed: " + e.getMessage());
            }
        }
        return errorPlan("Service busy. Please try again in a moment.");
    }

    /**
     * Makes a single HTTP call to Gemini.
     * Returns null if the server returned 429 (caller should retry).
     * Returns a Plan (possibly an error plan) for all other outcomes.
     */
    private Plan callOnce(LLMRequest request, int attempt) throws Exception {
        // Build Gemini request body
        JSONObject body = new JSONObject();

        // System instruction
        JSONObject systemInstruction = new JSONObject();
        JSONObject sysPart = new JSONObject();
        sysPart.put("text", request.getSystemPrompt());
        systemInstruction.put("parts", new JSONArray().put(sysPart));
        body.put("system_instruction", systemInstruction);

        // User message
        JSONObject userPart = new JSONObject();
        userPart.put("text", request.getUserQuery());
        JSONObject userContent = new JSONObject();
        userContent.put("role", "user");
        userContent.put("parts", new JSONArray().put(userPart));
        body.put("contents", new JSONArray().put(userContent));

        // Force JSON output
        JSONObject genConfig = new JSONObject();
        genConfig.put("response_mime_type", "application/json");
        body.put("generationConfig", genConfig);

        String fullUrl = API_URL + apiKey;
        Log.d(TAG, "Full URL length: " + fullUrl.length() + " key length: " + apiKey.length());

        Request httpRequest = new Request.Builder()
                .url(API_URL)
                .addHeader("x-goog-api-key", apiKey)
                .post(RequestBody.create(body.toString(), JSON))
                .build();

        try (Response response = http.newCall(httpRequest).execute()) {
            if (response.code() == 429) {
                Log.w(TAG, "Attempt " + attempt + ": 429 rate limited, will retry...");
                return null; // signal retry
            }
            if (response.code() == 403) {
                return errorPlan("API key invalid or not yet activated.");
            }
            if (!response.isSuccessful() || response.body() == null) {
                String errorBody = response.body() != null ? response.body().string() : "no body";
                Log.e(TAG, "Attempt " + attempt + " HTTP error: " + response.code() + " body: " + errorBody);
                Log.d(TAG, "API Key prefix: " + apiKey.substring(0, 8));
                if (response.code() == 429) return null;
                if (response.code() == 403) {
                    return errorPlan("API key invalid or not yet activated.");
                }
                return errorPlan("Network error: " + response.code());
            }

            String responseText = response.body().string();
            Log.d(TAG, "Gemini raw response: " + responseText);
            return parsePlan(responseText);
        }
    }

    // ── Response parsing ──────────────────────────────────────────────────

    private Plan parsePlan(String responseText) {
        try {
            JSONObject root = new JSONObject(responseText);
            String content = root
                    .getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
                    .trim();

            Log.d(TAG, "Gemini content: " + content);

            // Gemini may return a bare array or a {"steps":[], "message":""} object
            String assistantMessage = "Done.";
            JSONArray steps;
            if (content.trim().startsWith("[")) {
                // Bare array format: [{...}, {...}]
                steps = new JSONArray(content);
            } else {
                // Object format: {"steps": [...], "message": "..."}
                JSONObject parsed = new JSONObject(content);
                assistantMessage = parsed.optString("message", "Done.");
                steps = parsed.optJSONArray("steps");
            }

            if (steps == null || steps.length() == 0) {
                return new Plan(Collections.emptyList(), assistantMessage);
            }

            List<ActionSpec> actions = new ArrayList<>();
            for (int i = 0; i < steps.length(); i++) {
                JSONObject step = steps.getJSONObject(i);
                String tool = step.getString("tool");

                Map<String, String> args = new HashMap<>();
                JSONObject argsObj = step.optJSONObject("args");
                if (argsObj != null) {
                    Iterator<String> keys = argsObj.keys();
                    while (keys.hasNext()) {
                        String k = keys.next();
                        args.put(k, argsObj.getString(k));
                    }
                }

                actions.add(new ActionSpec(tool, args, RiskLevel.MEDIUM,
                        tool + "(" + args + ")"));
            }

            return new Plan(actions, assistantMessage);

        } catch (Exception e) {
            Log.e(TAG, "Failed to parse Gemini response", e);
            return errorPlan("Could not understand the response. Please try again.");
        }
    }

    private Plan errorPlan(String message) {
        return new Plan(Collections.emptyList(), message);
    }
}