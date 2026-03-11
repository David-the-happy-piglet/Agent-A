package edu.northeastern.agent_a.core.tools;

import android.content.Context;

import java.util.Map;

public interface Tool {
    String name();
    RiskLevel defaultRiskLevel();
    ToolSpec spec();
    ToolResult execute(Context context, Map<String, String> args);
}
