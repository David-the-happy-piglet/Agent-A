package edu.northeastern.agent_a.core.tools;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.util.LinkedHashMap;
import java.util.Map;

public class SmsComposeTool implements Tool {

    @Override
    public String name() { return "sms.compose"; }

    @Override
    public RiskLevel defaultRiskLevel() { return RiskLevel.MEDIUM; }

    @Override
    public ToolSpec spec() {
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("phone", "String — recipient phone number");
        params.put("body", "String — message text");
        return new ToolSpec("sms.compose",
                "Opens SMS compose screen with the given recipient and message body. No permission required.",
                params, RiskLevel.MEDIUM);
    }

    @Override
    public ToolResult execute(Context context, Map<String, String> args) {
        String phone = args.get("phone");
        String body = args.getOrDefault("body", "");
        if (phone == null || phone.trim().isEmpty()) {
            return ToolResult.fail("No phone number provided for SMS.");
        }
        try {
            Intent intent = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + phone.trim()));
            intent.putExtra("sms_body", body);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return ToolResult.success("Opened SMS compose for " + phone.trim()
                    + (body.isEmpty() ? "" : " with message: \"" + body + "\""));
        } catch (Exception e) {
            return ToolResult.fail("Failed to open SMS: " + e.getMessage());
        }
    }
}
