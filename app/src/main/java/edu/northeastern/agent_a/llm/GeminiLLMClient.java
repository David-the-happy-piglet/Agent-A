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

/**
 * GeminiLLMClient sends the prompt to Google Gemini and parses the JSON response into a Plan.
 * Uses OkHttp for HTTP requests.
 * The API key is passed in the request header (x-goog-api-key), not in the URL,
 * which is safer because header values do not appear in server logs.
 */
public class GeminiLLMClient implements LLMClient {

    private static final String TAG = "GeminiLLMClient";
    private static final String API_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent";
    private static final MediaType JSON = MediaType.get("application/json");

    private final String apiKey;
    private final OkHttpClient http = new OkHttpClient();

    /**
     * @param apiKey the Gemini API key, read from BuildConfig.GEMINI_API_KEY
     */
    public GeminiLLMClient(String apiKey) {
        this.apiKey = apiKey;
    }

    // ── Entry point ───────────────────────────────────────────────────────

    /**
     * Sends the request to Gemini with up to 3 retries.
     * If the server returns 429 (rate limit), callOnce() returns null
     * and we wait before the next attempt (2s, 4s, 6s).
     * Any other failure returns an error Plan immediately.
     *
     * @param request contains the system prompt and the user query
     * @return a Plan with actions, or an error Plan with no actions
     */
    @Override
    public Plan call(LLMRequest request) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                Plan result = callOnce(request, attempt);
                if (result != null) return result;
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

    // ── HTTP request ──────────────────────────────────────────────────────

    /**
     * Makes one HTTP call to Gemini.
     * Returns null if the server responded with 429 so call() can retry.
     * Returns a Plan (or error Plan) for all other responses.
     *
     * Request body structure:
     *   system_instruction  -- tool specs and instructions for the LLM
     *   contents            -- the user's message
     *   generationConfig    -- forces JSON output via response_mime_type
     *
     * @param request the prompt to send
     * @param attempt the current attempt number, used only for logging
     * @return a Plan, or null if the server rate-limited the request
     */
    private Plan callOnce(LLMRequest request, int attempt) throws Exception {
        JSONObject body = new JSONObject();

        JSONObject systemInstruction = new JSONObject();
        JSONObject sysPart = new JSONObject();
        sysPart.put("text", request.getSystemPrompt());
        systemInstruction.put("parts", new JSONArray().put(sysPart));
        body.put("system_instruction", systemInstruction);

        JSONObject userPart = new JSONObject();
        userPart.put("text", request.getUserQuery());
        JSONObject userContent = new JSONObject();
        userContent.put("role", "user");
        userContent.put("parts", new JSONArray().put(userPart));
        body.put("contents", new JSONArray().put(userContent));

        // Force JSON output so parsePlan() always receives valid JSON
        JSONObject genConfig = new JSONObject();
        genConfig.put("response_mime_type", "application/json");
        body.put("generationConfig", genConfig);

        Request httpRequest = new Request.Builder()
                .url(API_URL)
                .addHeader("x-goog-api-key", apiKey)
                .post(RequestBody.create(body.toString(), JSON))
                .build();

        try (Response response = http.newCall(httpRequest).execute()) {
            if (response.code() == 429) {
                Log.w(TAG, "Attempt " + attempt + ": rate limited, will retry...");
                return null;
            }
            if (response.code() == 403) {
                return errorPlan("API key invalid or not yet activated.");
            }
            if (!response.isSuccessful() || response.body() == null) {
                String errorBody = response.body() != null ? response.body().string() : "no body";
                Log.e(TAG, "HTTP error: " + response.code() + " body: " + errorBody);
                return errorPlan("Network error: " + response.code());
            }

            String responseText = response.body().string();
            Log.d(TAG, "Gemini raw response: " + responseText);
            return parsePlan(responseText);
        }
    }

    // ── Response parsing ──────────────────────────────────────────────────

    /**
     * Parses the Gemini JSON response into a Plan.
     * Gemini can return two formats:
     *   Format A (bare array):  [{tool, args}, ...]
     *   Format B (object):      { "message": "...", "steps": [{tool, args}, ...] }
     * Both are handled. Each step becomes one ActionSpec in the Plan.
     *
     * @param responseText the raw JSON string from Gemini
     * @return a Plan with the parsed actions, or an error Plan if parsing fails
     */
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

            String assistantMessage = "Done.";
            JSONArray steps;

            if (content.trim().startsWith("[")) {
                steps = new JSONArray(content);
            } else {
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

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Returns a Plan with no actions and an error message shown to the user.
     *
     * @param message the error text to display in the chat
     * @return an empty Plan carrying the error message
     */
    private Plan errorPlan(String message) {
        return new Plan(Collections.emptyList(), message);
    }
}