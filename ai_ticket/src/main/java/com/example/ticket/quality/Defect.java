package com.example.ticket.quality;

/**
 * 一条缺陷。code 用于统计与逻辑，message 用于展示给人看。
 */
public record Defect(String code, String field, String message) {

    public static Defect of(String code, String field, String message) {
        return new Defect(code, field, message);
    }
}
