package edu.northeastern.agent_a.core.tools;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A tool that pauses execution for a specified number of minutes.
 * Used for delayed reminders or actions.
 */
public class SystemWaitTool implements Tool {

    private static final String TAG = "SystemWaitTool";

    @Override
    public String name() { return "system.wait"; }

    @Override
    public RiskLevel defaultRiskLevel() { return RiskLevel.LOW; }

    @Override
    public ToolSpec spec() {
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("duration_minutes", "Integer — Number of minutes to wait before next step");
        return new ToolSpec(
                "system.wait",
                "Pauses execution for a specified number of minutes. Useful for reminders or delayed actions.",
                params,
                RiskLevel.LOW);
    }

    @Override
    public ToolResult execute(Context context, Map<String, String> args) {
        String durationStr = args.get("duration_minutes");
        if (durationStr == null || durationStr.isEmpty()) {
            return ToolResult.fail("Missing 'duration_minutes' parameter.");
        }

        try {
            int minutes = Integer.parseInt(durationStr);
            if (minutes <= 0) {
                return ToolResult.success("Wait duration was 0 or less, continuing immediately.");
            }

            Log.i(TAG, "Waiting for " + minutes + " minute(s)...");
            
            // For a production app, we would use AlarmManager. 
            // For this agent demonstration, we will block the background thread.
            // Note: This only works because the Executor runs on a background thread.
            Thread.sleep(minutes * 60 * 1000L);

            return ToolResult.success("Wait completed. Resuming next steps.");
        } catch (NumberFormatException e) {
            return ToolResult.fail("Invalid duration format: " + durationStr);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.fail("Wait interrupted.");
        }
    }
}
