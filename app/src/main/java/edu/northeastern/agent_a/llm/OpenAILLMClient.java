package edu.northeastern.agent_a.llm;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
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

/**
 * Real LLM client that calls an OpenAI-compatible Chat Completions API.
 * Currently configured for MiniMax. Uses only built-in Android APIs.
 * <p>
 * call() is BLOCKING — the caller must invoke it off the UI thread.
 */
public class OpenAILLMClient implements LLMClient {

    private static final String TAG = "OpenAILLMClient";

    private static final String API_KEY = BuildConfig.MINIMAX_API_KEY;

    // ╔═══════════════════════════════════════════════════════════════╗
    // ║  MiniMax model. Options:                                    ║
    // ║    "MiniMax-M1"     — fast, cost-efficient                  ║
    // ║    "MiniMax-Text-01" — supports vision + long context       ║
    // ╚═══════════════════════════════════════════════════════════════╝
    private static final String MODEL = "MiniMax-M2-her";

    private static final String API_URL =
            "https://api.minimax.io/v1/text/chatcompletion_v2";
    private static final int TIMEOUT_MS = 30_000;

    @Override
    public Plan call(LLMRequest request) {
        if (API_KEY == null || API_KEY.trim().isEmpty()) {
            return new Plan(Collections.emptyList(),
                    "MiniMax API key not configured. "
                            + "Add MINIMAX_API_KEY to local.properties.");
        }
        try {
            String responseBody = post(request);
            return parseResponse(responseBody);
        } catch (Exception e) {
            Log.e(TAG, "LLM call failed", e);
            return new Plan(Collections.emptyList(),
                    "LLM call failed: " + e.getMessage());
        }
    }

    // ── HTTP POST to OpenAI ─────────────────────────────────────────

    private String post(LLMRequest request) throws IOException, JSONException {
        JSONObject body = new JSONObject();
        body.put("model", MODEL);
        body.put("temperature", 0.2);

        JSONArray messages = new JSONArray();
        messages.put(new JSONObject()
                .put("role", "system")
                .put("content", request.getSystemPrompt()));
        messages.put(new JSONObject()
                .put("role", "user")
                .put("content", request.getUserQuery()));
        body.put("messages", messages);

        Log.d(TAG, "Sending to " + MODEL + "  prompt="
                + request.getSystemPrompt().length() + " chars");

        URL url = new URL(API_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + API_KEY.trim());
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                code >= 200 && code < 300
                        ? conn.getInputStream()
                        : conn.getErrorStream(),
                StandardCharsets.UTF_8));

        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        reader.close();
        conn.disconnect();

        if (code < 200 || code >= 300) {
            throw new IOException("API " + code + ": " + sb);
        }
        return sb.toString();
    }

    // ── Parse the LLM JSON response into a Plan ─────────────────────

    private Plan parseResponse(String responseBody) throws JSONException {
        JSONObject root = new JSONObject(responseBody);
        String content = root.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content");

        Log.d(TAG, "LLM raw content:\n" + content);

        JSONObject parsed = new JSONObject(content);
        String message = parsed.optString("message", "");
        JSONArray stepsArray = parsed.optJSONArray("steps");

        if (stepsArray == null || stepsArray.length() == 0) {
            return new Plan(Collections.emptyList(),
                    message.isEmpty() ? "No actions planned." : message);
        }

        List<ActionSpec> actions = new ArrayList<>();
        for (int i = 0; i < stepsArray.length(); i++) {
            JSONObject stepObj = stepsArray.getJSONObject(i);
            String toolName = stepObj.getString("tool");
            JSONObject argsObj = stepObj.optJSONObject("args");

            Map<String, String> args = new HashMap<>();
            if (argsObj != null) {
                Iterator<String> keys = argsObj.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    args.put(key, argsObj.getString(key));
                }
            }

            String desc = toolName + "(" + formatArgs(args) + ")";
            actions.add(new ActionSpec(toolName, args, RiskLevel.LOW, desc));
        }

        return new Plan(actions, message);
    }

    private static String formatArgs(Map<String, String> args) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : args.entrySet()) {
            if (!first) sb.append(", ");
            sb.append(e.getKey()).append("=\"").append(e.getValue()).append("\"");
            first = false;
        }
        return sb.toString();
    }
}
