package com.example.ticket.quality;

import com.example.ticket.extraction.JsonSchemaValidator;
import com.example.ticket.extraction.ValidationResult;
import com.example.ticket.extraction.Violation;
import com.example.ticket.template.FieldDef;
import com.example.ticket.template.TemplateSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 三维度核验 + 定级。
 *
 * 这是整个项目唯一能写完整单元测试的核心逻辑：
 * 输入全是确定性数据（schema / values / basis / summary / dialogue / now），
 * 输出全是可枚举的结论，不依赖模型、数据库、Spring 上下文。
 *
 * 定级顺序是固定的（idea.txt 9.8）：
 *   1 可决策性有阻断 → 红，后面两维不再定级
 *   2 忠实性/可决策性的降级项 → 黄
 *   3 自洽性的通用规则命中 → 黄
 *   4 其余 → 绿
 * 自洽性的 advisories 只进 messages，不改变级别。
 *
 * 结构合法性由抽取层保证（Extractor 会重试到结构合法为止），
 * 这里复用同一个校验器再查一遍，属于纵深防御：
 * 结构化检查不再写第二套，避免两处规则漂移。
 */
@Component
@RequiredArgsConstructor
public class QualityEvaluator {

    private final MissingAnalyzer missingAnalyzer;
    private final FaithfulnessChecker faithfulnessChecker;
    private final ConsistencyChecker consistencyChecker;
    private final JsonSchemaValidator schemaValidator;
    private final ObjectMapper mapper;

    /**
     * @param summary 抽取层的 summary（可为 null）
     * @param dialogue 原始对话全文，用于依据子串校验
     */
    public QualityReport evaluate(TemplateSchema schema,
                                  Map<String, Object> values,
                                  Map<String, String> basis,
                                  String summary,
                                  String dialogue,
                                  LocalDateTime now) {
        Map<String, Object> safeValues = values == null ? Map.of() : values;
        Map<String, String> safeBasis = basis == null ? Map.of() : basis;

        QualityReport report = new QualityReport();
        Map<String, FieldDef> fieldByKey = indexOf(schema);

        // ---------- 一、可决策性 ----------
        MissingReport missing = missingAnalyzer.analyze(schema, safeValues);
        report.getDecision().setMissingRequired(missing.required());
        report.getDecision().setMissingOptional(missing.optional());

        // 结构违规（纵深防御，正常情况下应为空）
        for (Defect d : structuralDefects(schema, safeValues, safeBasis, summary)) {
            report.getDecision().getBlocking().add(d);
        }
        for (String key : missing.required()) {
            report.getDecision().getBlocking().add(Defect.of("MISSING_REQUIRED", key,
                    "缺 1 项必填：" + labelOf(fieldByKey.get(key), key)));
        }
        for (String key : missing.optional()) {
            report.getDecision().getDegrading().add(Defect.of("OPTIONAL_MISSING", key,
                    "缺 1 项选填：" + labelOf(fieldByKey.get(key), key)));
        }

        // ---------- 二、忠实性 ----------
        FaithfulnessChecker.Result faith =
                faithfulnessChecker.check(schema, safeValues, safeBasis, dialogue);
        report.getFaithfulness().setCheckItems(new ArrayList<>(faith.items()));
        report.getFaithfulness().setCoverage(faith.coverage());
        report.getDecision().getDegrading().addAll(faith.defects());

        // ---------- 三、自洽性 ----------
        ConsistencyChecker.Result consistency = consistencyChecker.check(schema, safeValues, now);
        report.getConsistency().setViolations(new ArrayList<>(consistency.violations()));
        report.getConsistency().setAdvisories(new ArrayList<>(consistency.advisories()));

        if (summary == null || summary.isBlank()) {
            report.getDecision().getDegrading().add(Defect.of("SUMMARY_EMPTY", "summary",
                    "摘要为空，人工需要自己概括对话"));
        }

        // ---------- 四、度量 ----------
        report.setMetrics(metricsOf(schema, safeValues, faith.coverage()));

        // ---------- 五、定级 ----------
        report.setLevel(levelOf(report));

        // ---------- 六、原因句子 ----------
        report.setMessages(messagesOf(report, schema, fieldByKey));
        return report;
    }

    // ---------------------------------------------------------- 定级

    private QualityLevel levelOf(QualityReport report) {
        if (!report.getDecision().getBlocking().isEmpty()) {
            return QualityLevel.RED;
        }
        boolean degrading = !report.getDecision().getDegrading().isEmpty()
                || !report.getConsistency().getViolations().isEmpty();
        return degrading ? QualityLevel.YELLOW : QualityLevel.GREEN;
    }

    // ---------------------------------------------------------- 度量

    private QualityReport.Metrics metricsOf(TemplateSchema schema, Map<String, Object> values,
                                            double basisCoverage) {
        QualityReport.Metrics m = new QualityReport.Metrics();
        int total = schema.fieldCount();
        int filled = 0;
        int requiredTotal = 0;
        int requiredFilled = 0;
        int optionalTotal = 0;
        int optionalFilled = 0;

        for (FieldDef field : schema.getFields()) {
            boolean present = !missingAnalyzer.isMissing(values, field.getKey());
            if (present) {
                filled++;
            }
            if (field.isRequired()) {
                requiredTotal++;
                if (present) {
                    requiredFilled++;
                }
            } else {
                optionalTotal++;
                if (present) {
                    optionalFilled++;
                }
            }
        }

        m.setFillRate(ratio(filled, total));
        m.setRequiredRate(ratio(requiredFilled, requiredTotal));
        m.setOptionalCoverage(ratio(optionalFilled, optionalTotal));
        m.setBasisCoverage(round3(basisCoverage));
        return m;
    }

    private double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0.0 : round3((double) numerator / denominator);
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    // ---------------------------------------------------------- 结构（纵深防御）

    private List<Defect> structuralDefects(TemplateSchema schema, Map<String, Object> values,
                                           Map<String, String> basis, String summary) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("values", values);
        root.put("basis", basis);
        root.put("suggestion", null);
        root.put("summary", summary);
        JsonNode node = mapper.valueToTree(root);

        ValidationResult result = schemaValidator.validate(schema, node);
        if (result.isValid()) {
            return List.of();
        }

        List<Defect> defects = new ArrayList<>();
        for (Violation v : result.violations()) {
            // 依据缺失由忠实性维度负责，避免同一件事报两遍
            if ("BASIS_MISSING".equals(v.code())) {
                continue;
            }
            defects.add(Defect.of(v.code(), v.field(), v.message()));
        }
        return defects;
    }

    // ---------------------------------------------------------- 原因句子

    private List<String> messagesOf(QualityReport report, TemplateSchema schema,
                                    Map<String, FieldDef> fieldByKey) {
        List<String> messages = new ArrayList<>();

        List<String> required = report.getDecision().getMissingRequired();
        if (!required.isEmpty()) {
            messages.add("缺 " + required.size() + " 项必填："
                    + String.join("、", labelsOf(required, fieldByKey)));
        }
        List<String> optional = report.getDecision().getMissingOptional();
        if (!optional.isEmpty()) {
            messages.add("缺 " + optional.size() + " 项选填："
                    + String.join("、", labelsOf(optional, fieldByKey)));
        }
        for (Defect d : report.getDecision().getBlocking()) {
            if (!"MISSING_REQUIRED".equals(d.code())) {
                messages.add(d.message());
            }
        }
        for (Defect d : report.getDecision().getDegrading()) {
            if (!"OPTIONAL_MISSING".equals(d.code())) {
                messages.add(d.message());
            }
        }
        for (Defect d : report.getConsistency().getViolations()) {
            messages.add(d.message());
        }
        for (Defect d : report.getConsistency().getAdvisories()) {
            messages.add(d.message());
        }
        return messages;
    }

    private List<String> labelsOf(List<String> keys, Map<String, FieldDef> fieldByKey) {
        List<String> labels = new ArrayList<>(keys.size());
        for (String key : keys) {
            labels.add(labelOf(fieldByKey.get(key), key));
        }
        return labels;
    }

    private Map<String, FieldDef> indexOf(TemplateSchema schema) {
        Map<String, FieldDef> map = new LinkedHashMap<>();
        for (FieldDef f : schema.getFields()) {
            map.put(f.getKey(), f);
        }
        return map;
    }

    private String labelOf(FieldDef field, String key) {
        if (field == null) {
            return key;
        }
        return field.getLabel() == null || field.getLabel().isBlank()
                ? key : field.getLabel();
    }
}
