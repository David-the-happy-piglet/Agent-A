package edu.northeastern.agent_a.core.tools;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class SpotifyWebApiClient {

    private static final String API_BASE = "https://api.spotify.com/v1";

    private final Context context;
    private final SpotifyAuthManager authManager;

    public SpotifyWebApiClient(Context context, SpotifyAuthManager authManager) {
        this.context = context.getApplicationContext();
        this.authManager = authManager;
    }

    public String playQuery(String query) throws Exception {
        Track track = searchFirstTrack(query);
        if (track == null) {
            return "No Spotify track found for \"" + query + "\".";
        }

        putJson("/me/player/play", new JSONObject()
                .put("uris", new JSONArray().put(track.uri))
                .toString());
        return "Playing " + track.name + " by " + track.artist + ".";
    }

    public String playUri(String uri) throws Exception {
        JSONObject body = new JSONObject();
        if (uri.startsWith("spotify:track:")) {
            body.put("uris", new JSONArray().put(uri));
        } else {
            body.put("context_uri", uri);
        }

        putJson("/me/player/play", body.toString());
        return "Started Spotify playback for " + uri + ".";
    }

    public String resume() throws Exception {
        putJson("/me/player/play", "{}");
        return "Resumed Spotify playback.";
    }

    public String pause() throws Exception {
        request("PUT", "/me/player/pause", null);
        return "Paused Spotify playback.";
    }

    public String next() throws Exception {
        request("POST", "/me/player/next", null);
        return "Skipped to the next Spotify track.";
    }

    public String previous() throws Exception {
        request("POST", "/me/player/previous", null);
        return "Went back to the previous Spotify track.";
    }

    private Track searchFirstTrack(String query) throws Exception {
        String path = "/search?type=track&limit=1&q=" + Uri.encode(query);
        JSONObject response = new JSONObject(request("GET", path, null));
        JSONObject tracks = response.optJSONObject("tracks");
        if (tracks == null) {
            return null;
        }

        JSONArray items = tracks.optJSONArray("items");
        if (items == null || items.length() == 0) {
            return null;
        }

        JSONObject item = items.getJSONObject(0);
        String artist = "";
        JSONArray artists = item.optJSONArray("artists");
        if (artists != null && artists.length() > 0) {
            artist = artists.getJSONObject(0).optString("name", "");
        }

        return new Track(
                item.optString("uri", ""),
                item.optString("name", "Spotify track"),
                artist);
    }

    private void putJson(String path, String body) throws Exception {
        request("PUT", path, body);
    }

    private String request(String method, String path, String body) throws Exception {
        String token = authManager.getAccessToken(context);
        if (token.isEmpty()) {
            throw new IllegalStateException("Spotify is not connected.");
        }

        HttpURLConnection connection = null;
        try {
            URL url = new URL(API_BASE + path);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(15_000);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                try (OutputStream os = connection.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }

            int status = connection.getResponseCode();
            String response = readFully(status >= 400
                    ? connection.getErrorStream()
                    : connection.getInputStream());

            if (status == 204) {
                return "";
            }
            if (status >= 400) {
                throw new IllegalStateException(parseSpotifyError(status, response));
            }
            return response;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String parseSpotifyError(int status, String response) {
        try {
            JSONObject root = new JSONObject(response);
            JSONObject error = root.optJSONObject("error");
            if (error != null) {
                String reason = error.optString("reason", "");
                String message = error.optString("message", "");
                if ("NO_ACTIVE_DEVICE".equals(reason)) {
                    return "Spotify has no active playback device. Open Spotify once or start playback on a device, then try again.";
                }
                if (!message.isEmpty()) {
                    return "Spotify Web API error (" + status + "): " + message;
                }
            }
        } catch (Exception ignored) {
            // Fall back to raw response below.
        }

        if (status == 403) {
            return "Spotify refused playback control. This usually requires Spotify Premium.";
        }
        return "Spotify Web API error (" + status + "): " + response;
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

    private static class Track {
        final String uri;
        final String name;
        final String artist;

        Track(String uri, String name, String artist) {
            this.uri = uri;
            this.name = name;
            this.artist = artist;
        }
    }
}
