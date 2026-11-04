package com.example.ticket.extraction;

import com.example.ticket.template.FieldDef;
import com.example.ticket.template.FieldType;
import com.example.ticket.template.TemplateSchema;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * few-shot 自动生成器的纯单元测试。
 *
 * 它守的是「通用」的技术前提：字段由用户定义，
 * 示例必须机械生成，且生成出来的值必须合法
 * （尤其要满足 pattern，否则示例会教模型输出非法值）。
 */
class FewShotGeneratorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final FewShotGenerator generator = new FewShotGenerator(mapper);

    static TemplateSchema ecommerceSchema() {
        return new TemplateSchema(
                "电商售后工单", 3,
                "你是服装店的售后助手",
                "【退换货政策】签收 7 天内可无理由退换。",
                List.of("批准换货", "批准退货", "拒绝"),
                List.of(
                        new FieldDef("order_id", "订单号", FieldType.TEXT, true, null, "^\\d{8,20}$"),
                        new FieldDef("reason", "原因", FieldType.ENUM, true,
                                List.of("尺码不合适", "质量问题"), null),
                        new FieldDef("worn", "是否已穿着", FieldType.BOOLEAN, false, null, null),
                        new FieldDef("size_wanted", "希望换成的尺码", FieldType.TEXT, false, null,
                                "^(XS|S|M|L|XL|XXL)$"),
                        new FieldDef("count", "数量", FieldType.NUMBER, false, null, null),
                        new FieldDef("first_seen", "首次出现日期", FieldType.DATE, false, null, null),
                        new FieldDef("evidence", "照片", FieldType.IMAGE, false, null, null)));
    }

    @Test
    @DisplayName("生成的示例是合法 JSON，且覆盖全部字段 key")
    void exampleCoversAllFields() throws Exception {
        FewShot fewShot = generator.generate(ecommerceSchema());
        Map<String, Object> example = mapper.readValue(fewShot.exampleJson(),
                new TypeReference<Map<String, Object>>() {
                });

        assertTrue(example.containsKey("values"));
        assertTrue(example.containsKey("basis"));
        assertTrue(example.containsKey("suggestion"));
        assertTrue(example.containsKey("summary"));

        @SuppressWarnings("unchecked")
        Map<String, Object> values = (Map<String, Object>) example.get("values");
        assertEquals(7, values.size());
        for (FieldDef f : ecommerceSchema().getFields()) {
            assertTrue(values.containsKey(f.getKey()), "缺少字段 " + f.getKey());
        }
    }

    @Test
    @DisplayName("每种类型取到合法值：枚举取首项、布尔 false、image 为 null")
    void sampleValuesByType() throws Exception {
        FewShot fewShot = generator.generate(ecommerceSchema());
        Map<String, Object> example = mapper.readValue(fewShot.exampleJson(),
                new TypeReference<Map<String, Object>>() {
                });
        @SuppressWarnings("unchecked")
        Map<String, Object> values = (Map<String, Object>) example.get("values");

        assertEquals("尺码不合适", values.get("reason"));
        assertEquals(Boolean.FALSE, values.get("worn"));
        assertEquals(1, values.get("count"));
        assertEquals("2026-10-01", values.get("first_seen"));
        assertEquals(null, values.get("evidence"));
    }

    @Test
    @DisplayName("生成的文本值必须满足自己的 pattern")
    void textRespectsPattern() throws Exception {
        FewShot fewShot = generator.generate(ecommerceSchema());
        Map<String, Object> example = mapper.readValue(fewShot.exampleJson(),
                new TypeReference<Map<String, Object>>() {
                });
        @SuppressWarnings("unchecked")
        Map<String, Object> values = (Map<String, Object>) example.get("values");

        assertTrue(Pattern.matches("^\\d{8,20}$", String.valueOf(values.get("order_id"))),
                "订单号示例不满足 pattern: " + values.get("order_id"));
        assertTrue(Pattern.matches("^(XS|S|M|L|XL|XXL)$", String.valueOf(values.get("size_wanted"))),
                "尺码示例不满足 pattern: " + values.get("size_wanted"));
    }

    @Test
    @DisplayName("每个非空值的 basis 都是示例对话的原文片段")
    void basisIsSubstringOfDialogue() throws Exception {
        FewShot fewShot = generator.generate(ecommerceSchema());
        Map<String, Object> example = mapper.readValue(fewShot.exampleJson(),
                new TypeReference<Map<String, Object>>() {
                });
        @SuppressWarnings("unchecked")
        Map<String, Object> basis = (Map<String, Object>) example.get("basis");

        assertNotNull(basis.get("order_id"));
        for (Map.Entry<String, Object> e : basis.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            assertTrue(fewShot.dialogue().contains(String.valueOf(e.getValue())),
                    "basis." + e.getKey() + " 不在示例对话中: " + e.getValue());
        }
    }

    @Test
    @DisplayName("suggestion 取决策选项首项")
    void suggestionFromDecisionOptions() throws Exception {
        FewShot fewShot = generator.generate(ecommerceSchema());
        Map<String, Object> example = mapper.readValue(fewShot.exampleJson(),
                new TypeReference<Map<String, Object>>() {
                });
        assertEquals("批准换货", example.get("suggestion"));
    }

    @Test
    @DisplayName("没有字段时不崩，给出占位对话")
    void emptyFieldsDoNotCrash() {
        TemplateSchema empty = new TemplateSchema("空模板", 1, "r", null,
                List.of("a"), List.of());
        FewShot fewShot = generator.generate(empty);
        assertNotNull(fewShot.dialogue());
        assertFalse(fewShot.dialogue().isBlank());
    }
}
