package com.example.ticket.extraction;

import com.example.ticket.template.FieldDef;
import com.example.ticket.template.FieldType;
import com.example.ticket.template.TemplateSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结构校验的纯单元测试。
 *
 * 最重要的一条不是「能挑出错误」，而是「不把 null 当错误」：
 * 顾客没给订单号，模型填 null 是正确行为，
 * 若把它算成违规就会无限重试。这条边界必须被测试守住。
 */
class JsonSchemaValidatorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonSchemaValidator validator = new JsonSchemaValidator();

    private TemplateSchema schema() {
        return new TemplateSchema(
                "电商售后工单", 3, "你是售后助手", "知识",
                List.of("批准换货", "批准退货"),
                List.of(
                        new FieldDef("order_id", "订单号", FieldType.TEXT, true, null, "^\\d{8,20}$"),
                        new FieldDef("reason", "原因", FieldType.ENUM, true,
                                List.of("尺码不合适", "质量问题"), null),
                        new FieldDef("worn", "是否已穿着", FieldType.BOOLEAN, false, null, null),
                        new FieldDef("count", "数量", FieldType.NUMBER, false, null, null),
                        new FieldDef("first_seen", "首次出现", FieldType.DATE, false, null, null),
                        new FieldDef("evidence", "照片", FieldType.IMAGE, false, null, null)));
    }

    private ValidationResult check(String json) throws Exception {
        JsonNode node = mapper.readTree(json);
        return validator.validate(schema(), node);
    }

    private static final String VALID = """
            {"values":{"order_id":"2026100812345678","reason":"尺码不合适","worn":false,
                       "count":1,"first_seen":"2026-10-22","evidence":null},
             "basis":{"order_id":"订单号 2026100812345678","reason":"尺码不合适",
                      "worn":"没穿过","count":"一件","first_seen":"今天","evidence":null},
             "suggestion":"批准换货","summary":"用户希望换码"}
            """;

    @Test
    @DisplayName("合法输出没有任何违规")
    void valid() throws Exception {
        assertTrue(check(VALID).isValid());
    }

    @Test
    @DisplayName("值为 null 不算违规——这是信息缺失，不是格式错误")
    void nullValuesAreLegal() throws Exception {
        String json = """
                {"values":{"order_id":null,"reason":null,"worn":null,
                           "count":null,"first_seen":null,"evidence":null},
                 "basis":{"order_id":null,"reason":null,"worn":null,
                          "count":null,"first_seen":null,"evidence":null},
                 "suggestion":null,"summary":null}
                """;
        ValidationResult r = check(json);
        assertTrue(r.isValid(), r.describe());
    }

    @Test
    @DisplayName("缺字段 key 是违规（必须显式给 null）")
    void missingKey() throws Exception {
        String json = """
                {"values":{"order_id":"2026100812345678","reason":"尺码不合适"},
                 "basis":{"order_id":"x","reason":"y"},"suggestion":null,"summary":null}
                """;
        ValidationResult r = check(json);
        assertFalse(r.isValid());
        assertTrue(r.violations().stream().anyMatch(v -> v.code().equals("MISSING_KEY")));
    }

    @Test
    @DisplayName("出现未定义的字段是违规")
    void unknownKey() throws Exception {
        String json = VALID.replace("\"worn\":false", "\"worn\":false,\"ghost\":1");
        ValidationResult r = check(json);
        assertTrue(r.violations().stream().anyMatch(v -> v.code().equals("UNKNOWN_KEY")));
    }

    @Test
    @DisplayName("枚举越界是违规，且报错信息里带出候选")
    void enumOutOfRange() throws Exception {
        String json = VALID.replace("\"reason\":\"尺码不合适\"", "\"reason\":\"大小不合适\"");
        ValidationResult r = check(json);
        assertTrue(r.violations().stream().anyMatch(v -> v.code().equals("ENUM_OUT_OF_RANGE")));
        assertTrue(r.describe().contains("尺码不合适/质量问题"));
    }

    @Test
    @DisplayName("pattern 不匹配是违规")
    void patternMismatch() throws Exception {
        String json = VALID.replace("\"order_id\":\"2026100812345678\"",
                "\"order_id\":\"ABC123\"");
        ValidationResult r = check(json);
        assertTrue(r.violations().stream().anyMatch(v -> v.code().equals("PATTERN_MISMATCH")));
    }

    @Test
    @DisplayName("类型不符是违规")
    void typeMismatch() throws Exception {
        String json = VALID.replace("\"count\":1", "\"count\":\"一件\"")
                .replace("\"worn\":false", "\"worn\":\"否\"");
        ValidationResult r = check(json);
        assertTrue(r.violations().stream().anyMatch(v -> v.code().equals("TYPE_MISMATCH")));
        assertEquals(2, r.violations().stream()
                .filter(v -> v.code().equals("TYPE_MISMATCH")).count());
    }

    @Test
    @DisplayName("日期格式与不存在的日期都要挑出来")
    void dateProblems() throws Exception {
        assertTrue(check(VALID.replace("\"first_seen\":\"2026-10-22\"",
                        "\"first_seen\":\"2026/10/22\""))
                .violations().stream().anyMatch(v -> v.code().equals("DATE_FORMAT")));

        assertTrue(check(VALID.replace("\"first_seen\":\"2026-10-22\"",
                        "\"first_seen\":\"2026-02-30\""))
                .violations().stream().anyMatch(v -> v.code().equals("DATE_INVALID")));
    }

    @Test
    @DisplayName("image 字段被填了值就是违规——AI 不该假装看过图")
    void imageMustBeNull() throws Exception {
        String json = VALID.replace("\"evidence\":null", "\"evidence\":\"photo.jpg\"");
        ValidationResult r = check(json);
        assertTrue(r.violations().stream().anyMatch(v -> v.code().equals("IMAGE_NOT_NULL")));
    }

    @Test
    @DisplayName("有值但没有依据是违规")
    void basisMissing() throws Exception {
        String json = VALID.replace("\"worn\":\"没穿过\"", "\"worn\":null");
        ValidationResult r = check(json);
        assertTrue(r.violations().stream().anyMatch(v -> v.code().equals("BASIS_MISSING")));
    }

    @Test
    @DisplayName("建议必须来自决策选项")
    void suggestionMustBeAllowed() throws Exception {
        String json = VALID.replace("\"suggestion\":\"批准换货\"",
                "\"suggestion\":\"直接退款\"");
        ValidationResult r = check(json);
        assertTrue(r.violations().stream().anyMatch(
                v -> v.code().equals("SUGGESTION_NOT_ALLOWED")));
    }

    @Test
    @DisplayName("values 不是对象时直接失败，不继续检查字段")
    void valuesNotObject() throws Exception {
        ValidationResult r = check("{\"values\":[],\"basis\":{}}");
        assertEquals(1, r.violations().size());
        assertEquals("VALUES_NOT_OBJECT", r.violations().get(0).code());
    }
}
