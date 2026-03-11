package edu.northeastern.agent_a.core.tools;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Describes a tool's signature for inclusion in LLM prompts.
 * This is what the LLM "sees" to decide which function to call.
 */
public class ToolSpec {

    private final String name;
    private final String description;
    private final LinkedHashMap<String, String> parameters;
    private final RiskLevel riskLevel;

    public ToolSpec(String name, String description,
                    LinkedHashMap<String, String> parameters, RiskLevel riskLevel) {
        this.name = name;
        this.description = description;
        this.parameters = parameters;
        this.riskLevel = riskLevel;
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public Map<String, String> getParameters() { return parameters; }
    public RiskLevel getRiskLevel() { return riskLevel; }

    /**
     * Renders this tool as a function-signature block for the LLM prompt.
     * Example output:
     *   phone.dial(phone: String)
     *     Opens the phone dialer. No permission required.
     *     Risk: LOW
     */
    public String toPromptString() {
        StringBuilder sb = new StringBuilder();
        sb.append(name).append("(");
        int i = 0;
        for (Map.Entry<String, String> p : parameters.entrySet()) {
            if (i > 0) sb.append(", ");
            sb.append(p.getKey()).append(": ").append(p.getValue());
            i++;
        }
        sb.append(")\n");
        sb.append("  ").append(description).append("\n");
        sb.append("  Risk: ").append(riskLevel.name());
        return sb.toString();
    }
}
