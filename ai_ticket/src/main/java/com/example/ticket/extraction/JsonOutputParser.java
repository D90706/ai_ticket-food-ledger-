package com.example.ticket.extraction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 把模型返回的文本解析成 JSON。
 *
 * 单独抽出来是因为「模型加了 markdown 代码块 / 前后带解释」是
 * 最常见的失败模式之一，必须有独立、可测的解码逻辑。
 *
 * 它容忍三种本不该出现但实际会出现的包装：
 *   1. ```json ... ``` 代码块
 *   2. 前后带一句解释
 *   3. 首尾空白
 * 但不容忍：不是对象、没有 JSON。
 */
@Component
@RequiredArgsConstructor
public class JsonOutputParser {

    private final ObjectMapper mapper;

    public record ParseResult(JsonNode node, String error) {

        public boolean success() {
            return node != null;
        }

        public static ParseResult ok(JsonNode node) {
            return new ParseResult(node, null);
        }

        public static ParseResult fail(String error) {
            return new ParseResult(null, error);
        }
    }

    public ParseResult parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return ParseResult.fail("模型返回为空");
        }

        String cleaned = stripFence(raw);
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return ParseResult.fail("未找到 JSON 对象: " + brief(raw));
        }
        cleaned = cleaned.substring(start, end + 1);

        try {
            JsonNode node = mapper.readTree(cleaned);
            if (node == null || !node.isObject()) {
                return ParseResult.fail("顶层不是 JSON 对象");
            }
            return ParseResult.ok(node);
        } catch (Exception e) {
            return ParseResult.fail("JSON 解析失败: " + e.getMessage());
        }
    }

    /** 去掉 ```json ... ``` 包裹 */
    private String stripFence(String raw) {
        String s = raw.trim();
        if (!s.startsWith("```")) {
            return s;
        }
        int firstNewline = s.indexOf('\n');
        if (firstNewline < 0) {
            return s;
        }
        String body = s.substring(firstNewline + 1);
        int fenceEnd = body.lastIndexOf("```");
        if (fenceEnd >= 0) {
            body = body.substring(0, fenceEnd);
        }
        return body.trim();
    }

    private String brief(String raw) {
        String s = raw.trim().replace("\n", " ");
        return s.length() <= 80 ? s : s.substring(0, 80) + "...";
    }
}
