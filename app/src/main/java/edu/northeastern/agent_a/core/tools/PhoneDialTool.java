package edu.northeastern.agent_a.core.tools;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PhoneDialTool opens the system phone dialer with a given number pre-filled.
 * It does NOT call directly — the user still has to tap the call button.
 * Because of this, no CALL_PHONE permission is needed.
 *
 * ACTION_DIAL = open dialer only, no permission needed.
 * ACTION_CALL = call immediately, requires CALL_PHONE permission.
 */
public class PhoneDialTool implements Tool {

    // ── Tool identity ─────────────────────────────────────────────────────

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

    // ── Execution ─────────────────────────────────────────────────────────

    /**
     * Opens the system dialer with the given phone number pre-filled.
     * FLAG_ACTIVITY_NEW_TASK is required because the tool runs inside Executor,
     * which is not an Activity context.
     *
     * @param context the application context used to start the dialer Activity
     * @param args    must contain key "phone" with the number to dial
     * @return ToolResult.success() if the dialer opened, ToolResult.fail() otherwise
     */
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