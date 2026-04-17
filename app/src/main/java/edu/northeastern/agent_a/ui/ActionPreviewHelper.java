package edu.northeastern.agent_a.ui;

import android.app.Activity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import edu.northeastern.agent_a.R;
import edu.northeastern.agent_a.core.tools.ActionSpec;
import edu.northeastern.agent_a.core.tools.Plan;
import edu.northeastern.agent_a.core.tools.RiskLevel;

/**
 * ActionPreviewHelper shows a confirmation dialog before any plan runs.
 * It lists all planned actions so the user knows what is about to happen.
 * The result (confirm or cancel) is returned through the Callback interface.
 */
public class ActionPreviewHelper {

    // ── Callback interface ────────────────────────────────────────────────

    /**
     * Callback used by AgentChatActivity to react to the user's choice.
     * onConfirm() is called when the user presses Confirm.
     * onCancel() is called when the user presses Cancel.
     */
    public interface Callback {
        void onConfirm();
        void onCancel();
    }

    // ── Dialog builder ────────────────────────────────────────────────────

    /**
     * Builds and shows the confirmation dialog.
     * Each action is listed with its human-readable description and a risk label
     * (MEDIUM or HIGH RISK) if applicable.
     * setCancelable(false) forces the user to tap a button and prevents accidental dismissal.
     *
     * @param activity the current Activity, needed to attach the dialog
     * @param plan     the plan whose actions will be listed in the dialog
     * @param callback called with onConfirm() or onCancel() based on the user's choice
     */
    public static void show(Activity activity, Plan plan, Callback callback) {
        // Build the message: one numbered line per action, with risk labels where needed
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < plan.getActions().size(); i++) {
            ActionSpec a = plan.getActions().get(i);
            sb.append(i + 1).append(". ").append(a.getHumanDescription());
            if (a.getRiskLevel() == RiskLevel.HIGH) {
                sb.append("  [!] HIGH RISK");
            } else if (a.getRiskLevel() == RiskLevel.MEDIUM) {
                sb.append("  [!] MEDIUM");
            }
            sb.append("\n");
        }

        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.action_preview_title)
                .setMessage(sb.toString().trim())
                .setPositiveButton(R.string.btn_confirm, (d, w) -> callback.onConfirm())
                .setNegativeButton(R.string.btn_cancel,  (d, w) -> callback.onCancel())
                .setCancelable(false)
                .show();
    }
}