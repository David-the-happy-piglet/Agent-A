package edu.northeastern.agent_a.core.agent;

import edu.northeastern.agent_a.core.tools.ActionSpec;
import edu.northeastern.agent_a.core.tools.Plan;
import edu.northeastern.agent_a.core.tools.RiskLevel;

/**
 * Policy decides whether a Plan needs the user to confirm before running.
 * It also calculates the highest risk level across all actions in a Plan.
 */
public class Policy {

    // ── Confirmation check ────────────────────────────────────────────────

    /**
     * Returns true if the user must confirm before the plan runs.
     * Current behaviour: always true for any plan that has at least one action.
     * This is a safe default for a prototype so no action runs without user awareness.
     *
     * @param plan the plan to check
     * @return true if confirmation is required, false otherwise
     */
    public boolean requiresPreview(Plan plan) {
        return plan.hasActions();
    }

    // ── Risk calculation ──────────────────────────────────────────────────

    /**
     * Finds the highest RiskLevel among all actions in the plan.
     * Uses ordinal() to compare enum values (LOW=0, MEDIUM=1, HIGH=2).
     * The result is used by ActionPreviewHelper to show a warning label.
     *
     * @param plan the plan to evaluate
     * @return the highest RiskLevel found, or LOW if the plan has no actions
     */
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