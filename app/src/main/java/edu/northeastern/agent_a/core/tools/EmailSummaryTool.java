package edu.northeastern.agent_a.core.tools;

import android.content.Context;

import java.util.LinkedHashMap;
import java.util.Map;

public class EmailSummaryTool implements Tool {

    private static final String MOCK_SUMMARY =
            "\uD83D\uDCE7 Inbox Summary (3 unread):\n"
                    + "1. From: boss@company.com \u2014 \"Q1 Report Due\" \u2014 Reminder to submit by Friday.\n"
                    + "2. From: team@company.com \u2014 \"Lunch Plans\" \u2014 Team lunch at noon tomorrow.\n"
                    + "3. From: newsletter@tech.io \u2014 \"Weekly Digest\" \u2014 Top 5 AI trends this week.";

    @Override
    public String name() { return "email.summary"; }

    @Override
    public RiskLevel defaultRiskLevel() { return RiskLevel.LOW; }

    @Override
    public ToolSpec spec() {
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("mode", "String — 'inbox' for inbox summary");
        return new ToolSpec("email.summary",
                "Returns a summary of recent emails in the inbox (mock data for prototype).",
                params, RiskLevel.LOW);
    }

    @Override
    public ToolResult execute(Context context, Map<String, String> args) {
        return ToolResult.success(MOCK_SUMMARY);
    }
}
