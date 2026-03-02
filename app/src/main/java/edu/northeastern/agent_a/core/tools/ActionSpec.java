package edu.northeastern.agent_a.core.tools;

import java.util.Map;

public class ActionSpec {

    private final String toolName;
    private final Map<String, String> args;
    private final RiskLevel riskLevel;
    private final String humanDescription;

    public ActionSpec(String toolName, Map<String, String> args,
                      RiskLevel riskLevel, String humanDescription) {
        this.toolName = toolName;
        this.args = args;
        this.riskLevel = riskLevel;
        this.humanDescription = humanDescription;
    }

    public String getToolName() { return toolName; }
    public Map<String, String> getArgs() { return args; }
    public RiskLevel getRiskLevel() { return riskLevel; }
    public String getHumanDescription() { return humanDescription; }
}
