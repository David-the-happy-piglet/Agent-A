package edu.northeastern.agent_a.core.agent;

import android.content.Context;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import edu.northeastern.agent_a.core.tools.ActionSpec;
import edu.northeastern.agent_a.core.tools.Plan;
import edu.northeastern.agent_a.core.tools.Tool;
import edu.northeastern.agent_a.core.tools.ToolRegistry;
import edu.northeastern.agent_a.core.tools.ToolResult;

public class Executor {

    private static final String TAG = "Executor";
    private final ToolRegistry registry;

    public Executor(ToolRegistry registry) {
        this.registry = registry;
    }

    /**
     * Executes all actions in a plan sequentially.
     * Data resolved from earlier steps (e.g., phone from contacts.lookup)
     * is automatically substituted into later steps via the "[from_lookup]" placeholder.
     */
    public List<ToolResult> execute(Context context, Plan plan) {
        List<ToolResult> results = new ArrayList<>();
        Map<String, String> resolvedData = new HashMap<>();

        for (int i = 0; i < plan.getActions().size(); i++) {
            ActionSpec action = plan.getActions().get(i);
            Tool tool = registry.get(action.getToolName());

            if (tool == null) {
                results.add(ToolResult.fail("Unknown tool: " + action.getToolName()));
                break;
            }

            Map<String, String> args = new HashMap<>(action.getArgs());
            for (Map.Entry<String, String> entry : args.entrySet()) {
                if ("[from_lookup]".equals(entry.getValue())
                        && resolvedData.containsKey(entry.getKey())) {
                    args.put(entry.getKey(), resolvedData.get(entry.getKey()));
                }
            }

            ToolResult result = tool.execute(context, args);
            result = result.withAudit(action.getToolName(), action.getArgs());

            Log.d(TAG, "Step " + (i + 1) + " [" + action.getToolName() + "]: "
                    + result.getStatus() + " — " + result.displayText());

            if (result.getStatus() == ToolResult.Status.SUCCESS && result.getData() != null) {
                resolvedData.putAll(result.getData());
            }

            results.add(result);

            if (result.getStatus() == ToolResult.Status.FAIL
                    || result.getStatus() == ToolResult.Status.NEED_PERMISSION) {
                break;
            }
        }

        return results;
    }
}
