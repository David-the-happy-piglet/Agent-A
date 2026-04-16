package edu.northeastern.agent_a.core.tools;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.SystemClock;
import android.view.KeyEvent;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public class SpotifyControlTool implements Tool {

    private static final String SPOTIFY_PACKAGE = "com.spotify.music";

    @Override
    public String name() { return "spotify.control"; }

    @Override
    public RiskLevel defaultRiskLevel() { return RiskLevel.MEDIUM; }

    @Override
    public ToolSpec spec() {
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("action", "String — 'search' | 'play' | 'pause' | 'toggle' | 'next' | 'previous'");
        params.put("query", "String — song, artist, album, or playlist to search/play");
        params.put("uri", "String — optional spotify: URI to open directly");
        return new ToolSpec(
                "spotify.control",
                "Controls Spotify. Can open Spotify search/results, open a spotify: URI, or send media play/pause/next/previous commands to the active media session.",
                params,
                RiskLevel.MEDIUM);
    }

    @Override
    public ToolResult execute(Context context, Map<String, String> args) {
        if (!isSpotifyInstalled(context)) {
            return ToolResult.fail("Spotify is not installed on this device.");
        }

        String action = args.getOrDefault("action", "search").toLowerCase(Locale.US).trim();
        String query = args.getOrDefault("query", "").trim();
        String uri = args.getOrDefault("uri", "").trim();

        try {
            switch (action) {
                case "search":
                    if (query.isEmpty()) {
                        return openSpotifyHome(context);
                    }
                    openSpotifyUri(context, Uri.parse("spotify:search:" + Uri.encode(query)));
                    return ToolResult.success("Opened Spotify search for \"" + query + "\".");

                case "play":
                    if (!uri.isEmpty()) {
                        openSpotifyUri(context, Uri.parse(uri));
                        return ToolResult.success("Opened Spotify item.");
                    }
                    if (!query.isEmpty()) {
                        openSpotifyUri(context, Uri.parse("spotify:search:" + Uri.encode(query)));
                        sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY);
                        return ToolResult.success("Opened Spotify search for \"" + query + "\" and sent a play command.");
                    }
                    sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY);
                    return ToolResult.success("Sent play command to Spotify/current media session.");

                case "pause":
                case "stop":
                    sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PAUSE);
                    return ToolResult.success("Sent pause command to Spotify/current media session.");

                case "toggle":
                case "play_pause":
                    sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
                    return ToolResult.success("Sent play/pause command to Spotify/current media session.");

                case "next":
                    sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT);
                    return ToolResult.success("Sent next-track command to Spotify/current media session.");

                case "previous":
                    sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS);
                    return ToolResult.success("Sent previous-track command to Spotify/current media session.");

                default:
                    return ToolResult.fail("Unsupported Spotify action: " + action);
            }
        } catch (Exception e) {
            return ToolResult.fail("Spotify control failed: " + e.getMessage());
        }
    }

    private boolean isSpotifyInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(SPOTIFY_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private ToolResult openSpotifyHome(Context context) {
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(SPOTIFY_PACKAGE);
        if (intent == null) {
            return ToolResult.fail("Could not open Spotify.");
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
        return ToolResult.success("Opened Spotify.");
    }

    private void openSpotifyUri(Context context, Uri uri) {
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        intent.setPackage(SPOTIFY_PACKAGE);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    private void sendMediaKey(Context context, int keyCode) {
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) {
            throw new IllegalStateException("Audio service unavailable.");
        }

        long eventTime = SystemClock.uptimeMillis();
        KeyEvent down = new KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0);
        KeyEvent up = new KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0);
        audioManager.dispatchMediaKeyEvent(down);
        audioManager.dispatchMediaKeyEvent(up);
    }
}
