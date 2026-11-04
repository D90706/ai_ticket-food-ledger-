package com.example.ticket.extraction;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 输出解码的纯单元测试。
 *
 * 模型最常见的两种坏毛病都在这里：包 markdown 代码块、前后加解释。
 * 解码必须容忍，但不能容忍「压根不是 JSON」。
 */
class JsonOutputParserTest {

    private final JsonOutputParser parser = new JsonOutputParser(new ObjectMapper());

    @Test
    @DisplayName("纯 JSON 直接解析")
    void plainJson() {
        JsonOutputParser.ParseResult r = parser.parse("{\"values\":{},\"basis\":{}}");
        assertTrue(r.success());
        assertTrue(r.node().path("values").isObject());
    }

    @Test
    @DisplayName("容忍 ```json 代码块")
    void fencedJson() {
        String raw = """
                ```json
                {"values":{"a":1},"basis":{}}
                ```
                """;
        JsonOutputParser.ParseResult r = parser.parse(raw);
        assertTrue(r.success(), r.error());
        assertEquals(1, r.node().path("values").path("a").asInt());
    }

    @Test
    @DisplayName("容忍前后带解释文字")
    void surroundedByProse() {
        String raw = "好的，结果如下：{\"values\":{\"a\":1}} 以上就是抽取结果。";
        JsonOutputParser.ParseResult r = parser.parse(raw);
        assertTrue(r.success(), r.error());
    }

    @Test
    @DisplayName("没有 JSON 时报错，不返回空对象")
    void noJson() {
        JsonOutputParser.ParseResult r = parser.parse("抱歉，我无法完成这个任务。");
        assertFalse(r.success());
        assertTrue(r.error().contains("未找到 JSON"));
    }

    @Test
    @DisplayName("空返回与顶层数组都算失败")
    void emptyAndArray() {
        assertFalse(parser.parse("").success());
        assertFalse(parser.parse("   ").success());
        assertFalse(parser.parse("[1,2,3]").success());
    }

    @Test
    @DisplayName("语法错误的 JSON 报错")
    void brokenJson() {
        JsonOutputParser.ParseResult r = parser.parse("{\"values\":{\"a\":1},");
        assertFalse(r.success());
        assertTrue(r.error().contains("JSON 解析失败"));
    }
}
