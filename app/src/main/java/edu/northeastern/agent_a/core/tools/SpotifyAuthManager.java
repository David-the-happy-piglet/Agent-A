package edu.northeastern.agent_a.core.tools;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Base64;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

import edu.northeastern.agent_a.BuildConfig;

public class SpotifyAuthManager {

    private static final String PREFS = "spotify_auth";
    private static final String KEY_ACCESS_TOKEN = "access_token";
    private static final String KEY_REFRESH_TOKEN = "refresh_token";
    private static final String KEY_EXPIRES_AT = "expires_at";
    private static final String KEY_CODE_VERIFIER = "code_verifier";
    private static final String KEY_STATE = "state";
    private static final String KEY_PENDING_ACTION = "pending_action";
    private static final String KEY_PENDING_QUERY = "pending_query";
    private static final String KEY_PENDING_URI = "pending_uri";

    private static final String AUTH_URL = "https://accounts.spotify.com/authorize";
    private static final String TOKEN_URL = "https://accounts.spotify.com/api/token";
    private static final String SCOPES =
            "user-read-playback-state user-modify-playback-state user-read-currently-playing";

    public boolean isConfigured() {
        return !BuildConfig.SPOTIFY_CLIENT_ID.trim().isEmpty();
    }

    public boolean canHandleRedirect(Uri uri) {
        return uri != null
                && "agenta".equals(uri.getScheme())
                && "spotify-auth".equals(uri.getHost());
    }

    public String getAccessToken(Context context) throws Exception {
        SharedPreferences prefs = prefs(context);
        String accessToken = prefs.getString(KEY_ACCESS_TOKEN, "");
        long expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L);
        if (!accessToken.isEmpty() && System.currentTimeMillis() < expiresAt - 60_000L) {
            return accessToken;
        }

        String refreshToken = prefs.getString(KEY_REFRESH_TOKEN, "");
        if (!refreshToken.isEmpty()) {
            return refreshAccessToken(context, refreshToken);
        }

        return "";
    }

    public void startAuthorization(Context context, Map<String, String> pendingArgs) throws Exception {
        if (!isConfigured()) {
            throw new IllegalStateException("Spotify Client ID is missing. Add SPOTIFY_CLIENT_ID to local.properties.");
        }

        String verifier = randomUrlSafeString(64);
        String challenge = codeChallenge(verifier);
        String state = randomUrlSafeString(24);

        SharedPreferences.Editor editor = prefs(context).edit()
                .putString(KEY_CODE_VERIFIER, verifier)
                .putString(KEY_STATE, state);

        if (pendingArgs != null) {
            editor.putString(KEY_PENDING_ACTION, pendingArgs.getOrDefault("action", ""));
            editor.putString(KEY_PENDING_QUERY, pendingArgs.getOrDefault("query", ""));
            editor.putString(KEY_PENDING_URI, pendingArgs.getOrDefault("uri", ""));
        }
        editor.apply();

        Uri authUri = Uri.parse(AUTH_URL).buildUpon()
                .appendQueryParameter("client_id", BuildConfig.SPOTIFY_CLIENT_ID.trim())
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("redirect_uri", BuildConfig.SPOTIFY_REDIRECT_URI.trim())
                .appendQueryParameter("scope", SCOPES)
                .appendQueryParameter("state", state)
                .appendQueryParameter("code_challenge_method", "S256")
                .appendQueryParameter("code_challenge", challenge)
                .build();

        Intent intent = new Intent(Intent.ACTION_VIEW, authUri);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    public String handleRedirect(Context context, Uri uri) throws Exception {
        if (!canHandleRedirect(uri)) {
            return "Not a Spotify redirect.";
        }

        String error = uri.getQueryParameter("error");
        if (error != null && !error.trim().isEmpty()) {
            return "Spotify authorization failed: " + error;
        }

        String code = uri.getQueryParameter("code");
        String state = uri.getQueryParameter("state");
        SharedPreferences prefs = prefs(context);
        String expectedState = prefs.getString(KEY_STATE, "");
        String verifier = prefs.getString(KEY_CODE_VERIFIER, "");

        if (code == null || code.trim().isEmpty()) {
            return "Spotify authorization failed: missing code.";
        }
        if (expectedState.isEmpty() || !expectedState.equals(state)) {
            return "Spotify authorization failed: state mismatch.";
        }
        if (verifier.isEmpty()) {
            return "Spotify authorization failed: missing PKCE verifier.";
        }

        exchangeCode(context, code, verifier);
        prefs.edit()
                .remove(KEY_CODE_VERIFIER)
                .remove(KEY_STATE)
                .apply();
        return "Spotify connected.";
    }

    public Map<String, String> consumePendingCommand(Context context) {
        SharedPreferences prefs = prefs(context);
        String action = prefs.getString(KEY_PENDING_ACTION, "");
        String query = prefs.getString(KEY_PENDING_QUERY, "");
        String uri = prefs.getString(KEY_PENDING_URI, "");

        prefs.edit()
                .remove(KEY_PENDING_ACTION)
                .remove(KEY_PENDING_QUERY)
                .remove(KEY_PENDING_URI)
                .apply();

        Map<String, String> args = new HashMap<>();
        if (!action.isEmpty()) args.put("action", action);
        if (!query.isEmpty()) args.put("query", query);
        if (!uri.isEmpty()) args.put("uri", uri);
        return args;
    }

    private void exchangeCode(Context context, String code, String verifier) throws Exception {
        String body = form(
                "grant_type", "authorization_code",
                "code", code,
                "redirect_uri", BuildConfig.SPOTIFY_REDIRECT_URI.trim(),
                "client_id", BuildConfig.SPOTIFY_CLIENT_ID.trim(),
                "code_verifier", verifier);
        JSONObject response = postToken(body);
        saveTokenResponse(context, response);
    }

    private String refreshAccessToken(Context context, String refreshToken) throws Exception {
        String body = form(
                "grant_type", "refresh_token",
                "refresh_token", refreshToken,
                "client_id", BuildConfig.SPOTIFY_CLIENT_ID.trim());
        JSONObject response = postToken(body);
        saveTokenResponse(context, response);
        return response.optString(KEY_ACCESS_TOKEN, "");
    }

    private JSONObject postToken(String body) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(TOKEN_URL).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(15_000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

            try (OutputStream os = connection.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int status = connection.getResponseCode();
            String response = readFully(status >= 400
                    ? connection.getErrorStream()
                    : connection.getInputStream());
            if (status >= 400) {
                throw new IllegalStateException("Spotify token error (" + status + "): " + response);
            }
            return new JSONObject(response);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void saveTokenResponse(Context context, JSONObject response) {
        String accessToken = response.optString(KEY_ACCESS_TOKEN, "");
        String refreshToken = response.optString(KEY_REFRESH_TOKEN,
                prefs(context).getString(KEY_REFRESH_TOKEN, ""));
        int expiresIn = response.optInt("expires_in", 3600);

        prefs(context).edit()
                .putString(KEY_ACCESS_TOKEN, accessToken)
                .putString(KEY_REFRESH_TOKEN, refreshToken)
                .putLong(KEY_EXPIRES_AT, System.currentTimeMillis() + expiresIn * 1000L)
                .apply();
    }

    private SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private String codeChallenge(String verifier) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.encodeToString(hash, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    private String randomUrlSafeString(int bytes) {
        byte[] random = new byte[bytes];
        new SecureRandom().nextBytes(random);
        return Base64.encodeToString(random, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    private String form(String... keyValues) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keyValues.length - 1; i += 2) {
            if (i > 0) sb.append("&");
            sb.append(URLEncoder.encode(keyValues[i], "UTF-8"));
            sb.append("=");
            sb.append(URLEncoder.encode(keyValues[i + 1], "UTF-8"));
        }
        return sb.toString();
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
