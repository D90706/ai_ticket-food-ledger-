package com.example.ticket.llm;

/**
 * 一条对话消息。
 *
 * 刻意用 record：消息是不可变的值，不需要 setter。
 */
public record Message(String role, String content) {

    public static Message system(String content) {
        return new Message("system", content);
    }

    public static Message user(String content) {
        return new Message("user", content);
    }

    public static Message assistant(String content) {
        return new Message("assistant", content);
    }
}
