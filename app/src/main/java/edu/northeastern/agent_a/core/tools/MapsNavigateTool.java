package edu.northeastern.agent_a.core.tools;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.util.Map;

public class MapsNavigateTool implements Tool {

    @Override
    public String name() { return "maps.navigate"; }

    @Override
    public RiskLevel defaultRiskLevel() { return RiskLevel.LOW; }

    @Override
    public ToolResult execute(Context context, Map<String, String> args) {
        String destination = args.get("destination");
        if (destination == null || destination.trim().isEmpty()) {
            return ToolResult.fail("No destination provided.");
        }
        try {
            Uri navUri = Uri.parse("google.navigation:q=" + Uri.encode(destination.trim()));
            Intent intent = new Intent(Intent.ACTION_VIEW, navUri);
            intent.setPackage("com.google.android.apps.maps");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return ToolResult.success("Navigating to " + destination.trim());
        } catch (Exception e) {
            try {
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
