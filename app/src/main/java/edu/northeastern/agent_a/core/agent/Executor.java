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
    private final Map<String, String> sessionResolvedData = new HashMap<>();

    public Executor(ToolRegistry registry) {
        this.registry = registry;
    }

    /**
     * Executes all actions in a plan sequentially.
     */
    public List<ToolResult> execute(Context context, Plan plan) {
        List<ToolResult> results = new ArrayList<>();
        sessionResolvedData.clear();

        for (int i = 0; i < plan.getActions().size(); i++) {
            ActionSpec action = plan.getActions().get(i);
            ToolResult result = executeSingleAction(context, action);
            results.add(result);

            if (result.getStatus() != ToolResult.Status.SUCCESS) {
                break;
            }
        }
        return results;
    }

    public ToolResult executeSingleAction(Context context, ActionSpec action) {
        Tool tool = registry.get(action.getToolName());

        if (tool == null) {
            return ToolResult.fail("Unknown tool: " + action.getToolName());
        }

        Map<String, String> args = new HashMap<>(action.getArgs());
        for (Map.Entry<String, String> entry : args.entrySet()) {
            if ("[from_lookup]".equals(entry.getValue())
                    && sessionResolvedData.containsKey(entry.getKey())) {
                args.put(entry.getKey(), sessionResolvedData.get(entry.getKey()));
            }
        }

        ToolResult result = tool.execute(context, args);
        result = result.withAudit(action.getToolName(), action.getArgs());

        Log.d(TAG, "Executed [" + action.getToolName() + "]: "
                + result.getStatus() + " — " + result.displayText());

        if (result.getStatus() == ToolResult.Status.SUCCESS && result.getData() != null) {
            sessionResolvedData.putAll(result.getData());
        }

        return result;
    }
}
