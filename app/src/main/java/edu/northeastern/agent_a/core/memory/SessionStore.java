package edu.northeastern.agent_a.core.memory;

import java.util.ArrayList;
import java.util.List;

public class SessionStore {

    private static final int MAX_MESSAGES = 20;

    private final List<Message> messages = new ArrayList<>();
    private String lastPlanSummary = "";

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
