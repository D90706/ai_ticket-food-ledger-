package com.example.ticket.extraction;

import com.example.ticket.llm.Message;
import com.example.ticket.retrieval.FullInjectRetriever;
import com.example.ticket.template.FieldDef;
import com.example.ticket.template.FieldType;
import com.example.ticket.template.TemplateSchema;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prompt 拼装的纯单元测试。
 *
 * 断言的重点不是「文案好不好」，而是两件结构性的事：
 *   1. 七段顺序固定 —— 顺序变了，模型的注意力分布就变了
 *   2. 不含任何业务词 —— 一旦 prompt 里出现「订单」「售后」这类字眼，
 *      「一套 prompt 适配任意 schema」就变成了空话
 */
class PromptBuilderTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final FewShotGenerator fewShotGenerator = new FewShotGenerator(mapper);
    private final PromptBuilder builder =
            new PromptBuilder(new FullInjectRetriever(), fewShotGenerator);

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 22);

    /** 与电商完全不同的第二个 schema，用来证明拼装由 schema 驱动 */
    static TemplateSchema itSchema() {
        return new TemplateSchema(
                "IT 设备报修工单", 1,
                "你是公司 IT 服务台的接单助手",
                "【处理流程】硬件故障转技术支持。",
                List.of("指派技术支持", "上门维修"),
                List.of(
                        new FieldDef("device_no", "设备编号", FieldType.TEXT, true, null,
                                "^(PC|PR)-\\d{4}$"),
                        new FieldDef("fault_type", "故障类型", FieldType.ENUM, true,
                                List.of("硬件故障", "网络问题"), null),
                        new FieldDef("impact_scope", "影响范围", FieldType.ENUM, true,
                                List.of("仅本人", "全部门"), null),
                        new FieldDef("screenshot", "截图", FieldType.IMAGE, false, null, null)));
    }

    @Test
    @DisplayName("七段顺序固定")
    void sectionsInOrder() {
        String prompt = builder.systemPrompt(
                FewShotGeneratorTest.ecommerceSchema(), "对话", TODAY);
        String[] sections = {"【角色】", "【领域知识】", "【可用决策选项】", "【需要抽取的字段】",
                "【当前日期】", "【输出要求】", "【硬规则】", "【示例】"};
        int last = -1;
        for (String s : sections) {
            int idx = prompt.indexOf(s);
            assertTrue(idx > last, "段落顺序错误: " + s);
            last = idx;
        }
    }

    @Test
    @DisplayName("角色、知识、决策项、字段清单都被注入")
    void contentInjected() {
        String prompt = builder.systemPrompt(
                FewShotGeneratorTest.ecommerceSchema(), "对话", TODAY);

        assertTrue(prompt.contains("【角色】\n你是服装店的售后助手"));
        assertTrue(prompt.contains("签收 7 天内可无理由退换"));
        assertTrue(prompt.contains("批准换货 / 批准退货 / 拒绝"));
        assertTrue(prompt.contains("- order_id | 订单号 | text | 必填"));
        assertTrue(prompt.contains("枚举候选: 尺码不合适、质量问题"));
        assertTrue(prompt.contains("格式要求: ^\\d{8,20}$"));
        assertTrue(prompt.contains("2026-10-22"));
    }

    @Test
    @DisplayName("输出契约四字段与硬规则都在")
    void contractAndRules() {
        String prompt = builder.systemPrompt(
                FewShotGeneratorTest.ecommerceSchema(), "对话", TODAY);
        for (String key : new String[]{"\"values\"", "\"basis\"", "\"suggestion\"", "\"summary\""}) {
            assertTrue(prompt.contains(key), "缺少输出字段 " + key);
        }
        assertTrue(prompt.contains("禁止猜测"));
        assertTrue(prompt.contains("逐字摘录"));
        assertTrue(prompt.contains("image 类型字段你无法处理"));
    }

    @Test
    @DisplayName("固定文案不含任何业务词——通用性的硬约束")
    void fixedWordingIsBusinessNeutral() {
        // 用一个「完全中性」的 schema：字段名、标签、知识都不带业务含义。
        // 这样 prompt 里若出现业务词，只可能来自固定文案本身。
        TemplateSchema neutral = new TemplateSchema(
                "模板", 1, "你是助手", "（无）",
                List.of("选项甲", "选项乙"),
                List.of(
                        new FieldDef("f1", "字段一", FieldType.TEXT, true, null, null),
                        new FieldDef("f2", "字段二", FieldType.ENUM, true,
                                List.of("甲", "乙"), null)));

        String prompt = builder.systemPrompt(neutral, "对话内容", TODAY);

        // 「顾客」这条是实测抓出来的：固定规则原本写成「不要替顾客下结论」，
        // 等于把电商假设焊进了通用 prompt —— 换成 IT 报修模板时语义就错了。
        for (String word : new String[]{"订单", "售后", "退款", "商品", "顾客", "换货", "报修"}) {
            assertFalse(prompt.contains(word),
                    "固定文案里出现了业务词「" + word + "」，通用性被破坏");
        }
    }

    @Test
    @DisplayName("两个差异很大的 schema 拼出同样结构、不同内容")
    void twoSchemasShareStructure() {
        String a = builder.systemPrompt(FewShotGeneratorTest.ecommerceSchema(), "对话", TODAY);
        String b = builder.systemPrompt(itSchema(), "对话", TODAY);

        // 结构一致：都含七段
        for (String s : new String[]{"【角色】", "【领域知识】", "【可用决策选项】", "【需要抽取的字段】",
                "【当前日期】", "【输出要求】", "【硬规则】", "【示例】"}) {
            assertTrue(a.contains(s) && b.contains(s));
        }
        // 内容不同：各自的字段 key 只在自己的 prompt 里
        assertTrue(a.contains("order_id") && !a.contains("device_no"));
        assertTrue(b.contains("device_no") && !b.contains("order_id"));
        assertTrue(b.contains("枚举候选: 硬件故障、网络问题"));
    }

    @Test
    @DisplayName("消息列表是 system + user，user 携带对话原文")
    void messagesShape() {
        List<Message> messages = builder.build(
                FewShotGeneratorTest.ecommerceSchema(), "我收到的M码太大了", TODAY);
        assertEquals(2, messages.size());
        assertEquals("system", messages.get(0).role());
        assertEquals("user", messages.get(1).role());
        assertTrue(messages.get(1).content().contains("我收到的M码太大了"));
    }

    @Test
    @DisplayName("知识为空时给出占位说明，不产生空段")
    void blankKnowledge() {
        TemplateSchema noKnowledge = new TemplateSchema("t", 1, "r", null,
                List.of("a"), List.of(new FieldDef("k", "字段", FieldType.TEXT, true, null, null)));
        String prompt = builder.systemPrompt(noKnowledge, "对话", TODAY);
        assertTrue(prompt.contains("（本次未提供领域知识）"));
    }
}
