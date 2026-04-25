package edu.northeastern.agent_a.core.memory;

import java.util.ArrayList;
import java.util.List;

public class Message {

    public enum Role { USER, ASSISTANT }

    public static class SubTask {
        public String label;
        public boolean isRunning;
        public boolean isCompleted;
        public boolean isWaiting;

        public SubTask(String label) {
            this.label = label;
            this.isWaiting = true;
        }
    }

    private final Role role;
    private final String text;
    private final long timestamp;
    private List<SubTask> subTasks;

    public Message(Role role, String text) {
        this.role = role;
        this.text = text;
        this.timestamp = System.currentTimeMillis();
    }

    public Role getRole() { return role; }
    public String getText() { return text; }
    public long getTimestamp() { return timestamp; }

    public List<SubTask> getSubTasks() { return subTasks; }
    public void setSubTasks(List<SubTask> subTasks) { this.subTasks = subTasks; }
    
    public void updateSubTask(int index, boolean isRunning, boolean isCompleted, boolean isWaiting) {
        if (subTasks != null && index >= 0 && index < subTasks.size()) {
            SubTask st = subTasks.get(index);
            st.isRunning = isRunning;
            st.isCompleted = isCompleted;
            st.isWaiting = isWaiting;
        }
    }
}
