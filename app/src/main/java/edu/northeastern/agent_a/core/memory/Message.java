package edu.northeastern.agent_a.core.memory;

public class Message {

    public enum Role { USER, ASSISTANT }

    private final Role role;
    private final String text;
    private final long timestamp;

    public Message(Role role, String text) {
        this.role = role;
        this.text = text;
        this.timestamp = System.currentTimeMillis();
    }

    public Role getRole() { return role; }
    public String getText() { return text; }
    public long getTimestamp() { return timestamp; }
}
