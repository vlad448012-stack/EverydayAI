package com.everydayai.assistant;

public class ChatMessage {
    public static final int USER = 0;
    public static final int AI = 1;
    private final String text;
    private final int role;
    private final String time;

    public ChatMessage(String text, int role, String time) {
        this.text = text; this.role = role; this.time = time;
    }

    public String getText() { return text; }
    public int getRole() { return role; }
    public String getTime() { return time; }
    public boolean isUser() { return role == USER; }
}
