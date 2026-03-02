package edu.northeastern.agent_a.core.agent;

import edu.northeastern.agent_a.core.tools.ActionSpec;
import edu.northeastern.agent_a.core.tools.Plan;
import edu.northeastern.agent_a.core.tools.RiskLevel;

public class Policy {

    /** Prototype always requires preview before execution. */
    public boolean requiresPreview(Plan plan) {
        return plan.hasActions();
    }

    public RiskLevel overallRisk(Plan plan) {
        RiskLevel highest = RiskLevel.LOW;
        for (ActionSpec action : plan.getActions()) {
            if (action.getRiskLevel().ordinal() > highest.ordinal()) {
                highest = action.getRiskLevel();
            }
        }
        return highest;
    }
}
