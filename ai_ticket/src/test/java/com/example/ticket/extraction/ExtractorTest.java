package com.example.ticket.extraction;

import com.example.ticket.llm.LlmClient;
import com.example.ticket.llm.LlmOptions;
import com.example.ticket.llm.LlmResponse;
import com.example.ticket.llm.Message;
import com.example.ticket.retrieval.FullInjectRetriever;
import com.example.ticket.template.FieldDef;
import com.example.ticket.template.FieldType;
import com.example.ticket.template.TemplateSchema;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 抽取总装的单元测试。用脚本化的假 LlmClient，不碰网络。
 *
 * 测的是三条行为约定：
 *   1. 一次成功就不重试
 *   2. 结构违规则带纠错提示重试，修好就停
 *   3. 重试耗尽不抛异常，而是降级为「全员缺失」，交给核验层判红
 */
class ExtractorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final TemplateSchema schema = schemaOf();

    // ---------------------------------------------------------- 夹具

    private static TemplateSchema schemaOf() {
        return new TemplateSchema(
                "电商售后工单", 3, "你是售后助手", "知识",
                List.of("批准换货", "拒绝"),
                List.of(
                        new FieldDef("order_id", "订单号", FieldType.TEXT, true, null, "^\\d{8,20}$"),
                        new FieldDef("reason", "原因", FieldType.ENUM, true,
                                List.of("尺码不合适", "质量问题"), null),
                        new FieldDef("worn", "是否已穿着", FieldType.BOOLEAN, false, null, null),
                        new FieldDef("evidence", "照片", FieldType.IMAGE, false, null, null)));
    }

    private static final String VALID = """
            {"values":{"order_id":"2026100812345678","reason":"尺码不合适",
                       "worn":false,"evidence":null},
             "basis":{"order_id":"订单号 2026100812345678","reason":"尺码不合适",
                      "worn":"没穿过","evidence":null},
             "suggestion":"批准换货","summary":"用户希望换码"}
            """;

    private static final String BAD_ENUM = """
            {"values":{"order_id":"2026100812345678","reason":"大小不合适",
                       "worn":false,"evidence":null},
             "basis":{"order_id":"订单号 2026100812345678","reason":"大小不合适",
                      "worn":"没穿过","evidence":null},
             "suggestion":"批准换货","summary":"用户希望换码"}
            """;

    private static final String ALL_NULL = """
            {"values":{"order_id":null,"reason":null,"worn":null,"evidence":null},
             "basis":{"order_id":null,"reason":null,"worn":null,"evidence":null},
             "suggestion":null,"summary":null}
            """;

    /** 按脚本返回响应的假客户端，同时记录每次调用的消息 */
    static class ScriptedLlm implements LlmClient {
        private final Deque<String> script = new ArrayDeque<>();
        final List<List<Message>> calls = new ArrayList<>();

        ScriptedLlm(String... responses) {
            script.addAll(List.of(responses));
        }

        @Override
        public LlmResponse chat(List<Message> messages, LlmOptions options) {
            calls.add(List.copyOf(messages));
            String content = script.isEmpty() ? "" : script.poll();
            return new LlmResponse(content, "scripted", 10, 10, 1, "stop");
        }
    }

    private Extractor extractor(LlmClient client) {
        return new Extractor(
                new PromptBuilder(new FullInjectRetriever(), new FewShotGenerator(mapper)),
                client,
                new JsonOutputParser(mapper),
                new JsonSchemaValidator(),
                new RetryPolicy(3),
                mapper);
    }

    // ---------------------------------------------------------- 用例

    @Test
    @DisplayName("一次成功：不重试，四个字段都填上")
    void firstTrySuccess() {
        ScriptedLlm llm = new ScriptedLlm(VALID);
        ExtractionResult r = extractor(llm).extract(schema, "对话");

        assertEquals(1, llm.calls.size());
        assertEquals(1, r.getAttempts());
        assertFalse(r.isDegraded());
        assertEquals("2026100812345678", r.getValues().get("order_id"));
        assertEquals("尺码不合适", r.getValues().get("reason"));
        assertEquals(Boolean.FALSE, r.getValues().get("worn"));
        assertNull(r.getValues().get("evidence"));
        assertEquals("批准换货", r.getSuggestion());
    }

    @Test
    @DisplayName("枚举越界：带纠错提示重试一次，第二次修好")
    void retryThenSuccess() {
        ScriptedLlm llm = new ScriptedLlm(BAD_ENUM, VALID);
        ExtractionResult r = extractor(llm).extract(schema, "对话");

        assertEquals(2, llm.calls.size());
        assertEquals(2, r.getAttempts());
        assertFalse(r.isDegraded());
        assertEquals("尺码不合适", r.getValues().get("reason"));

        // 第二次调用的最后一条消息必须是纠错提示，且带上违规原因
        List<Message> second = llm.calls.get(1);
        Message last = second.get(second.size() - 1);
        assertEquals("user", last.role());
        assertTrue(last.content().contains("修正"), last.content());
        assertTrue(last.content().contains("不在候选内"), last.content());
        // 还应该把上一次的坏输出作为 assistant 消息带上
        assertEquals("assistant", second.get(second.size() - 2).role());
    }

    @Test
    @DisplayName("解析失败也走重试")
    void parseFailureRetries() {
        ScriptedLlm llm = new ScriptedLlm("抱歉，我无法完成。", VALID);
        ExtractionResult r = extractor(llm).extract(schema, "对话");

        assertEquals(2, llm.calls.size());
        assertFalse(r.isDegraded());
    }

    @Test
    @DisplayName("重试耗尽：降级为全员缺失，不抛异常")
    void exhaustRetriesDegrades() {
        ScriptedLlm llm = new ScriptedLlm(BAD_ENUM, BAD_ENUM, BAD_ENUM, VALID);
        ExtractionResult r = extractor(llm).extract(schema, "对话");

        // 最多 3 次，第四次即便能成功也不会再调
        assertEquals(3, llm.calls.size());
        assertEquals(3, r.getAttempts());
        assertTrue(r.isDegraded());
        assertFalse(r.getViolations().isEmpty());
        assertNull(r.getSuggestion());

        // 降级结果仍然包含全部字段 key（下游依赖这一点）
        assertEquals(4, r.getValues().size());
        for (FieldDef f : schema.getFields()) {
            assertTrue(r.getValues().containsKey(f.getKey()));
            assertNull(r.getValues().get(f.getKey()));
        }
    }

    @Test
    @DisplayName("全为 null 是合法结果，不该触发重试")
    void allNullIsNotAViolation() {
        ScriptedLlm llm = new ScriptedLlm(ALL_NULL);
        ExtractionResult r = extractor(llm).extract(schema, "对话");

        assertEquals(1, llm.calls.size());
        assertFalse(r.isDegraded());
        assertTrue(r.getValues().values().stream().allMatch(v -> v == null));
    }

    @Test
    @DisplayName("重试次数上限可配")
    void maxAttemptsConfigurable() {
        ScriptedLlm llm = new ScriptedLlm(BAD_ENUM, BAD_ENUM, BAD_ENUM, BAD_ENUM);
        Extractor single = new Extractor(
                new PromptBuilder(new FullInjectRetriever(), new FewShotGenerator(mapper)),
                llm, new JsonOutputParser(mapper), new JsonSchemaValidator(),
                new RetryPolicy(1), mapper);

        single.extract(schema, "对话");
        assertEquals(1, llm.calls.size());
    }
}
