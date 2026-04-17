package edu.northeastern.agent_a.core.tools;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

public class EmailSummaryTool implements Tool {

    private static final int MAX_ITEMS = 10;

    @Override
    public String name() { return "email.summary"; }

    @Override
    public RiskLevel defaultRiskLevel() { return RiskLevel.LOW; }

    @Override
    public ToolSpec spec() {
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("mode", "String — 'summary' or 'today_list'");
        return new ToolSpec("email.summary",
                "Reads locally captured Gmail notification snippets. If summary is not available, returns today's Gmail email list. Requires Notification Listener access.",
                params, RiskLevel.LOW);
    }

    @Override
    public ToolResult execute(Context context, Map<String, String> args) {
        if (!isNotificationListenerEnabled(context)) {
            openNotificationListenerSettings(context);
            return ToolResult.success(
                    "Gmail email access is not enabled yet.\n"
                            + "I opened Notification Access settings. Enable \"Agent-A Gmail Email Capture\", then new Gmail notifications will be cached for today's email list.\n"
                            + "Android/Gmail does not allow this prototype to directly read Gmail inbox contents without OAuth or notification access.");
        }

        try {
            JSONArray all = new JSONArray(context.getSharedPreferences(
                    GmailNotificationListenerService.PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(GmailNotificationListenerService.KEY_EMAILS, "[]"));

            JSONArray today = filterToday(all);
            if (today.length() == 0) {
                return ToolResult.success(
                        "No Gmail notifications from today are cached yet.\n"
                                + "I can't summarize Gmail directly from the Gmail app after opening it. "
                                + "Keep \"Agent-A Gmail Email Capture\" enabled, and new Gmail notifications received today will appear here.");
            }

            return ToolResult.success(formatTodayList(today));
        } catch (Exception e) {
            return ToolResult.fail("Could not read Gmail notification cache: " + e.getMessage());
        }
    }

    private boolean isNotificationListenerEnabled(Context context) {
        String enabled = Settings.Secure.getString(
                context.getContentResolver(), "enabled_notification_listeners");
        if (TextUtils.isEmpty(enabled)) {
            return false;
        }

        ComponentName componentName = new ComponentName(
                context, GmailNotificationListenerService.class);
        String expected = componentName.flattenToString();
        String[] listeners = enabled.split(":");
        for (String listener : listeners) {
            if (expected.equalsIgnoreCase(listener)) {
                return true;
            }
        }
        return false;
    }

    private void openNotificationListenerSettings(Context context) {
        Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    private JSONArray filterToday(JSONArray all) throws Exception {
        JSONArray today = new JSONArray();
        long startOfToday = startOfTodayMillis();

        for (int i = 0; i < all.length(); i++) {
            JSONObject item = all.getJSONObject(i);
            if (item.optLong("timestamp", 0L) >= startOfToday) {
                today.put(item);
            }
        }

        return today;
    }

    private long startOfTodayMillis() {
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0);
        calendar.set(java.util.Calendar.MINUTE, 0);
        calendar.set(java.util.Calendar.SECOND, 0);
        calendar.set(java.util.Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    private String formatTodayList(JSONArray today) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("Today's Gmail emails captured from notifications");
        if (today.length() > MAX_ITEMS) {
            sb.append(" (latest ").append(MAX_ITEMS).append(" of ").append(today.length()).append(")");
        }
        sb.append(":\n");
        sb.append("MockLLM cannot truly summarize Gmail content, so here is today's email list instead.\n");

        int count = Math.min(today.length(), MAX_ITEMS);
        for (int i = 0; i < count; i++) {
            JSONObject item = today.getJSONObject(i);
            sb.append(i + 1).append(". ")
                    .append(formatTime(item.optLong("timestamp", 0L)))
                    .append(" - ");

            String title = item.optString("title", "");
            String text = bestSnippet(item);
            if (!title.isEmpty()) {
                sb.append(title);
            } else {
                sb.append("Gmail");
            }
            if (!text.isEmpty()) {
                sb.append(": ").append(text);
            }
            sb.append("\n");
        }

        return sb.toString().trim();
    }

    private String bestSnippet(JSONObject item) {
        JSONArray lines = item.optJSONArray("lines");
        if (lines != null && lines.length() > 0) {
            StringBuilder sb = new StringBuilder();
            int lineCount = Math.min(lines.length(), 3);
            for (int i = 0; i < lineCount; i++) {
                String line = lines.optString(i, "").trim();
                if (!line.isEmpty()) {
                    if (sb.length() > 0) {
                        sb.append(" | ");
                    }
                    sb.append(line);
                }
            }
            if (sb.length() > 0) {
                return sb.toString();
            }
        }

        String bigText = item.optString("bigText", "").trim();
        if (!bigText.isEmpty()) {
            return bigText;
        }
        return item.optString("text", "").trim();
    }

    private String formatTime(long timestamp) {
        if (timestamp <= 0L) {
            return "time unknown";
        }
        SimpleDateFormat format = new SimpleDateFormat("HH:mm", Locale.US);
        format.setTimeZone(TimeZone.getDefault());
        return format.format(new Date(timestamp));
    }
}
