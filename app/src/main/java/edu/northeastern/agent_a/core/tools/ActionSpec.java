package edu.northeastern.agent_a.core.tools;

import java.util.Map;

public class ActionSpec {

    private final String toolName;
    private final Map<String, String> args;
    private final RiskLevel riskLevel;
    private final String humanDescription;
    private final int step;
    private final String label;

    public ActionSpec(String toolName, Map<String, String> args,
                      RiskLevel riskLevel, String humanDescription) {
        this(toolName, args, riskLevel, humanDescription, 1, "");
    }

    public ActionSpec(String toolName, Map<String, String> args,
                      RiskLevel riskLevel, String humanDescription,
                      int step, String label) {
        this.toolName = toolName;
        this.args = args;
        this.riskLevel = riskLevel;
        this.humanDescription = humanDescription;
        this.step = step;
        this.label = label;
    }

    public String getToolName() { return toolName; }
    public Map<String, String> getArgs() { return args; }
    public RiskLevel getRiskLevel() { return riskLevel; }
    public String getHumanDescription() {
        if (label != null && !label.isEmpty()) {
            return label;
        }
        return humanDescription;
    }
    public int getStep() { return step; }
    public String getLabel() { return label; }
}
