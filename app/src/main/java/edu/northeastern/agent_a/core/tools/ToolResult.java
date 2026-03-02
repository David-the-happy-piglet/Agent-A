package edu.northeastern.agent_a.core.tools;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class ToolResult {

    public enum Status { SUCCESS, FAIL, NEED_PERMISSION, NEED_USER_CONFIRM }

    private final Status status;
    private final String message;
    private final Map<String, String> data;
    private final String error;
    private final Map<String, String> audit;

    private ToolResult(Status status, String message,
                       Map<String, String> data, String error,
                       Map<String, String> audit) {
        this.status = status;
        this.message = message;
        this.data = data != null ? data : new HashMap<>();
        this.error = error;
        this.audit = audit != null ? audit : new HashMap<>();
    }

    public static ToolResult success(String message) {
        return new ToolResult(Status.SUCCESS, message, new HashMap<>(), null, new HashMap<>());
    }

    public static ToolResult success(String message, Map<String, String> data) {
        return new ToolResult(Status.SUCCESS, message, data, null, new HashMap<>());
    }

    public static ToolResult fail(String error) {
        return new ToolResult(Status.FAIL, null, new HashMap<>(), error, new HashMap<>());
    }

    public static ToolResult needPermission(String message) {
        return new ToolResult(Status.NEED_PERMISSION, message, new HashMap<>(), null, new HashMap<>());
    }

    public ToolResult withAudit(String toolName, Map<String, String> args) {
        Map<String, String> auditMap = new HashMap<>(this.audit);
        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        auditMap.put("timestamp", ts);
        auditMap.put("tool", toolName);
        auditMap.put("args", args.toString());
        auditMap.put("status", status.name());
        return new ToolResult(status, message, data, error, auditMap);
    }

    public Status getStatus() { return status; }
    public String getMessage() { return message; }
    public Map<String, String> getData() { return data; }
    public String getError() { return error; }
    public Map<String, String> getAudit() { return audit; }

    public String displayText() {
        if (message != null && !message.isEmpty()) return message;
        if (error != null && !error.isEmpty()) return error;
        return status.name();
    }
}
