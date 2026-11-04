package com.example.ticket.template;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模块 1 的纯单元测试：不碰数据库、不启动 Spring。
 *
 * 测的是两个最容易出错、又最基础的东西：
 *   1. FieldType 的 JSON 映射（小写字符串 ↔ 大写枚举）
 *   2. TemplateSchema 的自省方法（后面 MissingAnalyzer 全靠它）
 */
class FieldDefJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("fields JSON 能按小写 type 解析成枚举")
    void parseFieldsFromJson() throws Exception {
        String json = """
                [
                  {"key":"order_id","label":"订单号","type":"text","required":true,
                   "options":null,"pattern":"^\\\\d{8,20}$"},
                  {"key":"reason","label":"原因","type":"enum","required":true,
                   "options":["尺码不合适","质量问题"],"pattern":null},
                  {"key":"evidence","label":"照片","type":"image","required":false,
                   "options":null,"pattern":null}
                ]
                """;

        List<FieldDef> fields = mapper.readValue(json,
                mapper.getTypeFactory().constructCollectionType(List.class, FieldDef.class));

        assertEquals(3, fields.size());
        assertEquals(FieldType.TEXT, fields.get(0).getType());
        assertEquals(FieldType.ENUM, fields.get(1).getType());
        assertEquals(FieldType.IMAGE, fields.get(2).getType());
        assertTrue(fields.get(0).isRequired());
        assertFalse(fields.get(2).isRequired());
        assertEquals(2, fields.get(1).getOptions().size());
    }

    @Test
    @DisplayName("序列化回 JSON 时 type 仍然是小写字符串")
    void serializeBackToLowercase() throws Exception {
        FieldDef f = new FieldDef("worn", "是否已穿着", FieldType.BOOLEAN,
                false, null, null);
        String json = mapper.writeValueAsString(f);
        assertTrue(json.contains("\"type\":\"boolean\""), json);
        assertTrue(json.contains("\"required\":false"), json);
    }

    @Test
    @DisplayName("未知字段类型必须报错，不能静默降级")
    void unknownTypeThrows() {
        String json = "[{\"key\":\"x\",\"label\":\"x\",\"type\":\"file\",\"required\":true}]";
        assertThrows(Exception.class, () -> mapper.readValue(json,
                mapper.getTypeFactory().constructCollectionType(List.class, FieldDef.class)));
    }

    @Test
    @DisplayName("required 缺失时必须能被识别出来（模板保存要拒绝这种字段）")
    void requiredMayBeAbsent() throws Exception {
        String json = "[{\"key\":\"x\",\"label\":\"x\",\"type\":\"text\"}]";
        List<FieldDef> fields = mapper.readValue(json,
                mapper.getTypeFactory().constructCollectionType(List.class, FieldDef.class));
        // 业务上这是非法的，但类型层面必须能表达「没填」这个状态
        assertEquals(null, fields.get(0).getRequired());
    }

    @Test
    @DisplayName("TemplateSchema 的必填/选填拆分与 key 集合")
    void schemaIntrospection() {
        TemplateSchema schema = new TemplateSchema(
                "电商售后工单", 3, "你是售后助手", "知识",
                List.of("批准换货", "拒绝"),
                List.of(
                        new FieldDef("order_id", "订单号", FieldType.TEXT, true, null, null),
                        new FieldDef("reason", "原因", FieldType.ENUM, true,
                                List.of("尺码不合适"), null),
                        new FieldDef("worn", "是否已穿着", FieldType.BOOLEAN, false, null, null),
                        new FieldDef("evidence", "照片", FieldType.IMAGE, false, null, null)));

        assertEquals(2, schema.requiredFields().size());
        assertEquals(2, schema.optionalFields().size());
        assertEquals(4, schema.fieldCount());
        assertEquals(4, schema.keys().size());
        assertTrue(schema.find("worn").isPresent());
        assertFalse(schema.find("not_exist").isPresent());
        assertTrue(schema.find("evidence").orElseThrow().getType().isAiUnreadable());
        assertFalse(schema.find("order_id").orElseThrow().getType().isAiUnreadable());
    }
}
