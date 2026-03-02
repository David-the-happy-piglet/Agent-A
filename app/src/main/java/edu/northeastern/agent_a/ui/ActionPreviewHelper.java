package edu.northeastern.agent_a.ui;

import android.app.Activity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import edu.northeastern.agent_a.R;
import edu.northeastern.agent_a.core.tools.ActionSpec;
import edu.northeastern.agent_a.core.tools.Plan;
import edu.northeastern.agent_a.core.tools.RiskLevel;

public class ActionPreviewHelper {

    public interface Callback {
        void onConfirm();
        void onCancel();
    }

    public static void show(Activity activity, Plan plan, Callback callback) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < plan.getActions().size(); i++) {
            ActionSpec a = plan.getActions().get(i);
            sb.append(i + 1).append(". ").append(a.getHumanDescription());
            if (a.getRiskLevel() == RiskLevel.HIGH) {
                sb.append("  \u26A0\uFE0F HIGH RISK");
            } else if (a.getRiskLevel() == RiskLevel.MEDIUM) {
                sb.append("  \u26A0 MEDIUM");
            }
            sb.append("\n");
        }

        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.action_preview_title)
                .setMessage(sb.toString().trim())
                .setPositiveButton(R.string.btn_confirm, (d, w) -> callback.onConfirm())
                .setNegativeButton(R.string.btn_cancel, (d, w) -> callback.onCancel())
                .setCancelable(false)
                .show();
    }
}
