package com.example.ticket.extraction;

/**
 * 一条结构违规。
 *
 * code 用于日志与统计，message 是给人看的（也会拼进重试提示里
 * 反馈给模型），field 为空表示是整体结构问题而非某个字段的问题。
 */
public record Violation(String code, String field, String message) {

    public static Violation of(String code, String field, String message) {
        return new Violation(code, field, message);
    }

    @Override
    public String toString() {
        return (field == null || field.isBlank())
                ? "[" + code + "] " + message
                : "[" + code + "] " + field + " — " + message;
    }
}
