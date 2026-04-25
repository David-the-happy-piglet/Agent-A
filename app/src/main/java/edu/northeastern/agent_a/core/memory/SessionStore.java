package edu.northeastern.agent_a.core.memory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SessionStore {

    private static final int MAX_MESSAGES = 20;

    private final List<Message> messages = new ArrayList<>();
    private String lastPlanSummary = "";
    private String lastToolOutput = "None";
    private int remainingCompoundCommands = 0;
    private final Map<String, String> userPreferences = new HashMap<>();

    public void addMessage(Message msg) {
        messages.add(msg);
        if (messages.size() > MAX_MESSAGES) {
            compress();
        }
    }

    public List<Message> getMessages() {
        return messages;
    }

    public String getLastPlanSummary() {
        return lastPlanSummary;
    }

    public void setLastPlanSummary(String summary) {
        this.lastPlanSummary = summary;
    }

    public String getLastToolOutput() { return lastToolOutput; }
    public void setLastToolOutput(String output) { this.lastToolOutput = output; }

    public int getRemainingCompoundCommands() { return remainingCompoundCommands; }
    public void setRemainingCompoundCommands(int count) { this.remainingCompoundCommands = count; }

    public Map<String, String> getUserPreferences() { return userPreferences; }
    public void addUserPreference(String key, String value) { userPreferences.put(key, value); }

    private void compress() {
        int halfSize = messages.size() / 2;
        StringBuilder sb = new StringBuilder("[Summary of earlier conversation: ");
        for (int i = 0; i < halfSize; i++) {
            Message m = messages.get(i);
            sb.append(m.getRole().name()).append(": ").append(m.getText()).append(" | ");
        }
        sb.append("]");

        List<Message> remaining = new ArrayList<>(messages.subList(halfSize, messages.size()));
        messages.clear();
        messages.add(new Message(Message.Role.ASSISTANT, sb.toString()));
        messages.addAll(remaining);
    }
}
