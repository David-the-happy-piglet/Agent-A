package edu.northeastern.agent_a.core.tools;

import android.app.SearchManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public class SpotifyControlTool implements Tool {

    private static final String TAG = "SpotifyControlTool";
    private static final String SPOTIFY_PACKAGE = "com.spotify.music";
    private static final String SPOTIFY_RECEIVER = "com.spotify.music.internal.receiver.MediaButtonReceiver";

    @Override
    public String name() { return "spotify.control"; }

    @Override
    public RiskLevel defaultRiskLevel() { return RiskLevel.MEDIUM; }

    @Override
    public ToolSpec spec() {
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("action", "String — 'search' | 'play' | 'pause' | 'toggle' | 'next' | 'previous'");
        params.put("query", "String — song, artist, album, or playlist to search/play");
        return new ToolSpec(
                "spotify.control",
                "Controls Spotify playback and search. To play a specific song, use action='play' and query='song name'.",
                params,
                RiskLevel.MEDIUM);
    }

    @Override
    public ToolResult execute(Context context, Map<String, String> args) {
        if (!isSpotifyInstalled(context)) {
            return ToolResult.fail("Spotify is not installed on this device.");
        }

        String action = args.getOrDefault("action", "play").toLowerCase(Locale.US).trim();
        String query = args.getOrDefault("query", "").trim();

        Log.i(TAG, "Executing: " + action + " (Query: " + query + ")");

        try {
            switch (action) {
                case "play":
                    if (!query.isEmpty()) {
                        return launchAndPlay(context, query);
                    }
                    forceWakeupSpotify(context);
                    sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY);
                    return ToolResult.success("Sent play command to Spotify.");

                case "pause":
                    sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PAUSE);
                    return ToolResult.success("Paused playback.");

                case "next":
                    sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT);
                    return ToolResult.success("Skipped track.");
                
                case "previous":
                    sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS);
                    return ToolResult.success("Previous track.");

                case "toggle":
                    sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
                    return ToolResult.success("Toggled playback.");

                default:
                    if (!query.isEmpty()) return launchAndPlay(context, query);
                    return openSpotifyHome(context);
            }
        } catch (Exception e) {
            Log.e(TAG, "Spotify tool failure", e);
            return ToolResult.fail("Failed to control Spotify: " + e.getMessage());
        }
    }

    private ToolResult launchAndPlay(Context context, String query) {
        // Use standard MediaStore intent for playing from search
        Intent intent = new Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH);
        intent.setPackage(SPOTIFY_PACKAGE);
        intent.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*");
        intent.putExtra(SearchManager.QUERY, query);
        // This extra helps some versions of Spotify start playback immediately
        intent.putExtra("android.intent.extra.play_playback", true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        startActivityOnMainThread(context, intent);

        // Schedule multiple "Play" attempts to ensure playback starts after the app loads
        Handler handler = new Handler(Looper.getMainLooper());
        
        // Attempt 1: Targeted broadcast (low latency)
        handler.postDelayed(() -> {
            Log.d(TAG, "Sending targeted Play broadcast");
            forceWakeupSpotify(context);
        }, 2000);

        // Attempt 2: Global media key dispatch (fallback)
        handler.postDelayed(() -> {
            Log.d(TAG, "Sending global Play media key");
            sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY);
        }, 4000);
        
        return ToolResult.success("Searching and playing '" + query + "' on Spotify.");
    }

    private ToolResult openSpotifyHome(Context context) {
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(SPOTIFY_PACKAGE);
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivityOnMainThread(context, intent);
            return ToolResult.success("Opened Spotify.");
        }
        return ToolResult.fail("Could not find Spotify launch intent.");
    }

    private void startActivityOnMainThread(Context context, Intent intent) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                context.startActivity(intent);
                Log.i(TAG, "startActivity triggered on Main Thread");
            } catch (Exception e) {
                Log.e(TAG, "Main thread startActivity failed", e);
                try {
                    intent.setPackage(null);
                    context.startActivity(intent);
                } catch (Exception e2) {
                    Log.e(TAG, "Complete launch failure", e2);
                }
            }
        });
    }

    private void forceWakeupSpotify(Context context) {
        Intent intent = new Intent(Intent.ACTION_MEDIA_BUTTON);
        intent.setComponent(new ComponentName(SPOTIFY_PACKAGE, SPOTIFY_RECEIVER));
        long time = SystemClock.uptimeMillis();
        
        intent.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(time, time, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0));
        context.sendBroadcast(intent);
        intent.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(time, time, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY, 0));
        context.sendBroadcast(intent);
    }

    private void sendMediaKey(Context context, int keyCode) {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            long time = SystemClock.uptimeMillis();
            am.dispatchMediaKeyEvent(new KeyEvent(time, time, KeyEvent.ACTION_DOWN, keyCode, 0));
            am.dispatchMediaKeyEvent(new KeyEvent(time, time, KeyEvent.ACTION_UP, keyCode, 0));
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
}
