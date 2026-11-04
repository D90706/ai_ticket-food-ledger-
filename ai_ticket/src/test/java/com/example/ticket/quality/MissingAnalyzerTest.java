package com.example.ticket.quality;

import com.example.ticket.template.FieldDef;
import com.example.ticket.template.FieldType;
import com.example.ticket.template.TemplateSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 缺失判定的单元测试。
 *
 * 重点是「假值不是缺失」：false / 0 是明确的答案，
 * 若被当成缺失，系统会追着顾客把已经回答过的问题再问一遍。
 */
class MissingAnalyzerTest {

    private final MissingAnalyzer analyzer = new MissingAnalyzer();

    private TemplateSchema schema() {
        return new TemplateSchema("t", 1, "r", "k", List.of("a"),
                List.of(
                        new FieldDef("order_id", "订单号", FieldType.TEXT, true, null, null),
                        new FieldDef("reason", "原因", FieldType.ENUM, true,
                                List.of("尺码不合适"), null),
                        new FieldDef("worn", "是否已穿着", FieldType.BOOLEAN, false, null, null),
                        new FieldDef("count", "数量", FieldType.NUMBER, false, null, null)));
    }

    @Test
    @DisplayName("按必填/选填分流缺失项")
    void splitsRequiredAndOptional() {
        // 注意不能用 Map.of —— 它不允许 null 值，而「值为 null」正是要测的东西
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("order_id", "2026100812345678");
        values.put("reason", null);
        values.put("worn", null);
        values.put("count", 2);

        MissingReport r = analyzer.analyze(schema(), values);

        assertEquals(List.of("reason"), r.required());
        assertEquals(List.of("worn"), r.optional());
        assertEquals(List.of("reason", "worn"), r.all());
        assertTrue(r.hasRequiredMissing());
    }

    @Test
    @DisplayName("false 与 0 不是缺失——它们是明确的答案")
    void falsyValuesAreNotMissing() {
        MissingReport r = analyzer.analyze(schema(), Map.of(
                "order_id", "2026100812345678",
                "reason", "尺码不合适",
                "worn", Boolean.FALSE,
                "count", 0));

        assertTrue(r.isEmpty(), "false / 0 不应被判为缺失: " + r.all());
    }

    @Test
    @DisplayName("空字符串算缺失")
    void blankStringIsMissing() {
        MissingReport r = analyzer.analyze(schema(), Map.of(
                "order_id", "   ",
                "reason", "尺码不合适",
                "worn", true,
                "count", 1));
        assertEquals(List.of("order_id"), r.required());
    }

    @Test
    @DisplayName("键完全缺失也按缺失处理（不依赖键是否存在）")
    void absentKeyIsMissing() {
        MissingReport r = analyzer.analyze(schema(), Map.of());
        assertEquals(2, r.required().size());
        assertEquals(2, r.optional().size());
    }

    @Test
    @DisplayName("空 values 或 null values 不崩")
    void nullSafe() {
        MissingReport r = analyzer.analyze(schema(), null);
        assertEquals(4, r.all().size());
        assertFalse(r.isEmpty());
    }
}
