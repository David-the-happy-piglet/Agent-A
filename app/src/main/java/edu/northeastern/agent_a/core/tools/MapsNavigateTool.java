package edu.northeastern.agent_a.core.tools;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MapsNavigateTool opens turn-by-turn navigation to a given destination.
 * It tries Google Maps first. If Google Maps is not installed, it falls back
 * to a generic geo: URI that any installed map app can handle.
 * No permission is needed because we are only opening an external app.
 */
public class MapsNavigateTool implements Tool {

    // ── Tool identity ─────────────────────────────────────────────────────

    @Override
    public String name() { return "maps.navigate"; }

    @Override
    public RiskLevel defaultRiskLevel() { return RiskLevel.LOW; }

    @Override
    public ToolSpec spec() {
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("destination", "String — address or place name");
        return new ToolSpec("maps.navigate",
                "Opens Google Maps navigation to the destination. No permission required.",
                params, RiskLevel.LOW);
    }

    // ── Execution ─────────────────────────────────────────────────────────

    /**
     * Tries to open Google Maps navigation. Falls back to a geo: URI if Google Maps
     * is not available on the device.
     *
     * Primary:  google.navigation URI with package set to Google Maps.
     * Fallback: geo:0,0?q= URI, handled by any map app on the device.
     *
     * @param context the application context used to start the navigation Activity
     * @param args    must contain key "destination" with the address or place name
     * @return ToolResult.success() if navigation opened, ToolResult.fail() if both attempts fail
     */
    @Override
    public ToolResult execute(Context context, Map<String, String> args) {
        String destination = args.get("destination");
        if (destination == null || destination.trim().isEmpty()) {
            return ToolResult.fail("No destination provided.");
        }
        try {
            // Primary: open turn-by-turn navigation in Google Maps
            Uri navUri = Uri.parse("google.navigation:q=" + Uri.encode(destination.trim()));
            Intent intent = new Intent(Intent.ACTION_VIEW, navUri);
            intent.setPackage("com.google.android.apps.maps");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return ToolResult.success("Navigating to " + destination.trim());
        } catch (Exception e) {
            try {
                // Fallback: geo URI works with any installed map app
                Uri geoUri = Uri.parse("geo:0,0?q=" + Uri.encode(destination.trim()));
                Intent fallback = new Intent(Intent.ACTION_VIEW, geoUri);
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(fallback);
                return ToolResult.success("Opened maps for " + destination.trim());
            } catch (Exception e2) {
                return ToolResult.fail("Failed to open navigation: " + e2.getMessage());
            }
        }
    }
}