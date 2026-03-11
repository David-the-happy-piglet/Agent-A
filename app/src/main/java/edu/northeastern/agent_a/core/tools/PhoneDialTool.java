package edu.northeastern.agent_a.core.tools;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.util.LinkedHashMap;
import java.util.Map;

public class PhoneDialTool implements Tool {

    @Override
    public String name() { return "phone.dial"; }

    @Override
    public RiskLevel defaultRiskLevel() { return RiskLevel.LOW; }

    @Override
    public ToolSpec spec() {
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("phone", "String — the phone number to dial");
        return new ToolSpec("phone.dial",
                "Opens the phone dialer with the given number. No permission required.",
                params, RiskLevel.LOW);
    }

    @Override
    public ToolResult execute(Context context, Map<String, String> args) {
        String phone = args.get("phone");
        if (phone == null || phone.trim().isEmpty()) {
            return ToolResult.fail("No phone number provided.");
        }
        try {
            Intent intent = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phone.trim()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return ToolResult.success("Opened dialer for " + phone.trim());
        } catch (Exception e) {
            return ToolResult.fail("Failed to open dialer: " + e.getMessage());
        }
    }
}
