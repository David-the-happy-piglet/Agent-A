package edu.northeastern.agent_a.core.tools;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.util.LinkedHashMap;
import java.util.Map;

public class GmailComposeTool implements Tool {

    @Override
    public String name() { return "gmail.compose"; }

    @Override
    public RiskLevel defaultRiskLevel() { return RiskLevel.MEDIUM; }

    @Override
    public ToolSpec spec() {
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("recipient", "String — recipient email address");
        params.put("subject", "String — email subject");
        params.put("body", "String — email body content");
        return new ToolSpec(
                "gmail.compose",
                "Opens the Gmail compose screen with pre-filled fields. Use this whenever the user wants to send an email.",
                params,
                RiskLevel.MEDIUM);
    }

    @Override
    public ToolResult execute(Context context, Map<String, String> args) {
        String recipient = args.getOrDefault("recipient", "").trim();
        String subject = args.getOrDefault("subject", "").trim();
        String body = args.getOrDefault("body", "").trim();

        try {
            // Using mailto: URI for maximum compatibility with Gmail and other mail apps
            String uriString = "mailto:" + Uri.encode(recipient) +
                    "?subject=" + Uri.encode(subject) +
                    "&body=" + Uri.encode(body);
            
            Intent intent = new Intent(Intent.ACTION_SENDTO, Uri.parse(uriString));
            intent.setPackage("com.google.android.gm"); // Explicitly target Gmail
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            // Fallback if Gmail is not installed or package name is different
            if (intent.resolveActivity(context.getPackageManager()) == null) {
                intent.setPackage(null);
            }

            context.startActivity(intent);
            return ToolResult.success("Opened Gmail compose for: " + (recipient.isEmpty() ? "new recipient" : recipient));
        } catch (Exception e) {
            return ToolResult.fail("Failed to open Gmail: " + e.getMessage());
        }
    }
}
