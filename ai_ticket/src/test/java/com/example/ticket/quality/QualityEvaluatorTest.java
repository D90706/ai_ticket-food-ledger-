package com.example.ticket.quality;

import com.example.ticket.extraction.JsonSchemaValidator;
import com.example.ticket.template.FieldDef;
import com.example.ticket.template.FieldType;
import com.example.ticket.template.TemplateSchema;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 三维度核验与定级的单元测试。
 *
 * 这是整个项目里最值得写满测试的类：输入纯度极高、输出可枚举，
 * 一旦定级规则错了，前面所有模块的价值都会打折。
 */
class QualityEvaluatorTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 20, 9, 41);

    private final ObjectMapper mapper = new ObjectMapper();

    private final QualityEvaluator evaluator = new QualityEvaluator(
            new MissingAnalyzer(),
            new FaithfulnessChecker(),
            new ConsistencyChecker(),
            new JsonSchemaValidator(),
            mapper);

    private TemplateSchema schema() {
        return new TemplateSchema(
                "电商售后工单", 3, "你是售后助手", "知识",
                List.of("批准换货", "拒绝"),
                List.of(
                        new FieldDef("order_id", "订单号", FieldType.TEXT, true, null, "^\\d{8,20}$"),
                        new FieldDef("reason", "原因", FieldType.ENUM, true,
                                List.of("尺码不合适", "质量问题"), null),
                        new FieldDef("worn", "是否已穿着", FieldType.BOOLEAN, false, null, null),
                        new FieldDef("size_wanted", "希望换成的尺码", FieldType.TEXT, false, null, null),
                        new FieldDef("evidence", "商品问题照片", FieldType.IMAGE, false, null, null)));
    }

    private static final String DIALOGUE =
            "您好，我上周买的卫衣订单号 2026100812345678，收到的M码太大了，"
                    + "我平时穿S的，想换成S码。衣服吊牌还没剪，也没穿过。";

    private Map<String, Object> fullValues() {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("order_id", "2026100812345678");
        v.put("reason", "尺码不合适");
        v.put("worn", false);
        v.put("size_wanted", "S");
        v.put("evidence", null);
        return v;
    }

    private Map<String, String> fullBasis() {
        Map<String, String> b = new LinkedHashMap<>();
        b.put("order_id", "订单号 2026100812345678");
        b.put("reason", "收到的M码太大了");
        b.put("worn", "也没穿过");
        b.put("size_wanted", "想换成S码");
        b.put("evidence", null);
        return b;
    }

    private Map<String, Object> emptyValues() {
        Map<String, Object> v = new LinkedHashMap<>();
        for (String k : List.of("order_id", "reason", "worn", "size_wanted", "evidence")) {
            v.put(k, null);
        }
        return v;
    }

    private Map<String, String> emptyBasis() {
        Map<String, String> b = new LinkedHashMap<>();
        for (String k : List.of("order_id", "reason", "worn", "size_wanted", "evidence")) {
            b.put(k, null);
        }
        return b;
    }

    // ---------------------------------------------------------- 定级

    @Test
    @DisplayName("选填缺失 → 黄；只影响颜色，不阻断决策")
    void missingOptionalIsYellow() {
        QualityReport r = evaluator.evaluate(schema(), fullValues(), fullBasis(),
                "用户希望换成S码", DIALOGUE, NOW);

        assertEquals(QualityLevel.YELLOW, r.getLevel());
        assertTrue(r.getDecision().getBlocking().isEmpty());
        assertEquals(1, r.getDecision().getDegrading().size());
        assertEquals("evidence", r.getDecision().getDegrading().get(0).field());
        assertEquals(List.of("evidence"), r.getDecision().getMissingOptional());
        assertTrue(r.getMessages().contains("缺 1 项选填：商品问题照片"));
        assertFalse(r.getLevel().canDecideDirectly());
        assertTrue(r.getLevel().requiresBasisReview());
    }

    @Test
    @DisplayName("必填缺失 → 红，且不再看后面两维")
    void missingRequiredIsRed() {
        QualityReport r = evaluator.evaluate(schema(), emptyValues(), emptyBasis(),
                null, "我买的裤子有问题，能不能退？", NOW);

        assertEquals(QualityLevel.RED, r.getLevel());
        assertEquals(2, r.getDecision().getBlocking().size());
        assertEquals(List.of("order_id", "reason"), r.getDecision().getMissingRequired());
        assertEquals(0.0, r.getMetrics().getFillRate(), 0.0001);
        assertEquals(0.0, r.getMetrics().getRequiredRate(), 0.0001);
        assertTrue(r.getMessages().contains("缺 2 项必填：订单号、原因"));
    }

    @Test
    @DisplayName("全部齐全且依据可校验 → 绿")
    void completeIsGreen() {
        Map<String, Object> values = fullValues();
        values.put("evidence", null);
        // 只剩 evidence 时是黄；把它填上才该是绿
        values.put("size_wanted", "S");
        values.put("worn", true);

        Map<String, String> basis = fullBasis();
        basis.put("worn", "也没穿过");

        QualityReport r = evaluator.evaluate(schema(), values, basis,
                "用户希望换成S码", DIALOGUE, NOW);

        // evidence 仍是 null（选填），所以这里是黄而不是绿——这正是设计意图：
        // 有选填缺失就提示「待核实」，比假装完美更有用。
        assertEquals(QualityLevel.YELLOW, r.getLevel());
        assertEquals(1, r.getMetrics().getRequiredRate(), 0.0001);
        assertEquals(0.667, r.getMetrics().getOptionalCoverage(), 0.0001);
    }

    @Test
    @DisplayName("没有任何缺失与缺陷 → 绿")
    void noDefectsIsGreen() {
        TemplateSchema onlyRequired = new TemplateSchema(
                "t", 1, "r", "k", List.of("批准换货"),
                List.of(new FieldDef("order_id", "订单号", FieldType.TEXT, true, null, null),
                        new FieldDef("reason", "原因", FieldType.ENUM, true,
                                List.of("尺码不合适"), null)));
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("order_id", "2026100812345678");
        values.put("reason", "尺码不合适");
        Map<String, String> basis = new LinkedHashMap<>();
        basis.put("order_id", "订单号 2026100812345678");
        basis.put("reason", "M码太大了");

        QualityReport r = evaluator.evaluate(onlyRequired, values, basis,
                "用户希望换码", DIALOGUE, NOW);

        assertEquals(QualityLevel.GREEN, r.getLevel());
        assertTrue(r.getLevel().canDecideDirectly());
        assertEquals(1.0, r.getMetrics().getFillRate(), 0.0001);
        assertEquals(1.0, r.getMetrics().getBasisCoverage(), 0.0001);
        assertTrue(r.getMessages().isEmpty(), r.getMessages().toString());
    }

    // ---------------------------------------------------------- 忠实性

    @Test
    @DisplayName("依据不在原文 → 黄，且 checkItem 标记 inDialogue=false")
    void basisNotInDialogue() {
        Map<String, String> basis = fullBasis();
        basis.put("reason", "衣服太紧了");     // 原文里没有这句话

        QualityReport r = evaluator.evaluate(schema(), fullValues(), basis,
                "用户希望换码", DIALOGUE, NOW);

        assertEquals(QualityLevel.YELLOW, r.getLevel());
        BasisCheck check = r.getFaithfulness().getCheckItems().stream()
                .filter(c -> c.field().equals("reason")).findFirst().orElseThrow();
        assertTrue(check.hasBasis());
        assertFalse(check.inDialogue());
        assertFalse(check.passed());
        assertTrue(r.getDecision().getDegrading().stream()
                .anyMatch(d -> d.code().equals("BASIS_NOT_IN_DIALOGUE")));
    }

    @Test
    @DisplayName("有值但没给依据 → 黄")
    void basisMissing() {
        Map<String, String> basis = fullBasis();
        basis.put("worn", null);

        QualityReport r = evaluator.evaluate(schema(), fullValues(), basis,
                "用户希望换码", DIALOGUE, NOW);

        assertEquals(QualityLevel.YELLOW, r.getLevel());
        assertTrue(r.getDecision().getDegrading().stream()
                .anyMatch(d -> d.code().equals("BASIS_MISSING")));
    }

    @Test
    @DisplayName("摘要为空 → 黄（可以决策，但人工要自己概括）")
    void emptySummaryIsYellow() {
        QualityReport r = evaluator.evaluate(schema(), fullValues(), fullBasis(),
                "   ", DIALOGUE, NOW);
        assertEquals(QualityLevel.YELLOW, r.getLevel());
        assertTrue(r.getDecision().getDegrading().stream()
                .anyMatch(d -> d.code().equals("SUMMARY_EMPTY")));
    }

    @Test
    @DisplayName("依据覆盖率只统计非空字段")
    void basisCoverageDenominator() {
        QualityReport r = evaluator.evaluate(schema(), fullValues(), fullBasis(),
                "s", DIALOGUE, NOW);
        // 4 个非空字段，4 个都有依据
        assertEquals(1.0, r.getMetrics().getBasisCoverage(), 0.0001);
        assertEquals(4, r.getFaithfulness().getCheckItems().size());
    }

    // ---------------------------------------------------------- 自洽性

    @Test
    @DisplayName("未来日期只提示，不改变级别")
    void futureDateIsAdvisoryOnly() {
        TemplateSchema withDate = new TemplateSchema("t", 1, "r", "k", List.of("a"),
                List.of(new FieldDef("first_seen", "首次出现", FieldType.DATE, false, null, null)));
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("first_seen", "2027-01-01");
        Map<String, String> basis = new LinkedHashMap<>();
        basis.put("first_seen", "2027-01-01");

        QualityReport r = evaluator.evaluate(withDate, values, basis, "s",
                "对话里说 2027-01-01", NOW);

        assertEquals(1, r.getConsistency().getAdvisories().size());
        assertTrue(r.getConsistency().getViolations().isEmpty());
        // 只有提示，没有降级项 → 仍是绿
        assertEquals(QualityLevel.GREEN, r.getLevel());
        assertTrue(r.getMessages().stream().anyMatch(m -> m.contains("未来")));
    }

    // ---------------------------------------------------------- 纵深防御

    @Test
    @DisplayName("结构违规（枚举越界）也被判红——即使抽取层本该拦住")
    void structuralViolationIsBlocking() {
        Map<String, Object> values = fullValues();
        values.put("reason", "大小不合适");     // 不在候选内

        QualityReport r = evaluator.evaluate(schema(), values, fullBasis(),
                "s", DIALOGUE, NOW);

        assertEquals(QualityLevel.RED, r.getLevel());
        assertTrue(r.getDecision().getBlocking().stream()
                .anyMatch(d -> d.code().equals("ENUM_OUT_OF_RANGE")));
    }

    // ---------------------------------------------------------- 度量

    @Test
    @DisplayName("四个度量按 mock 的数值计算")
    void metrics() {
        QualityReport r = evaluator.evaluate(schema(), fullValues(), fullBasis(),
                "s", DIALOGUE, NOW);

        assertEquals(0.8, r.getMetrics().getFillRate(), 0.0001);
        assertEquals(1.0, r.getMetrics().getRequiredRate(), 0.0001);
        assertEquals(0.667, r.getMetrics().getOptionalCoverage(), 0.0001);
        assertEquals(1.0, r.getMetrics().getBasisCoverage(), 0.0001);
    }

    @Test
    @DisplayName("全空输入不崩，度量为 0")
    void allEmpty() {
        QualityReport r = evaluator.evaluate(schema(),
                new HashMap<>(emptyValues()), new HashMap<>(emptyBasis()),
                null, null, NOW);
        assertEquals(QualityLevel.RED, r.getLevel());
        assertEquals(0.0, r.getMetrics().getFillRate(), 0.0001);
        assertEquals(0.0, r.getMetrics().getBasisCoverage(), 0.0001);
    }
}
