package com.example.ledger.entry;

import com.example.ledger.llm.LlmClient;
import com.example.ledger.template.TemplateField;
import com.example.ledger.template.TemplateSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 抽取层。
 *
 * 四道处理，一道都不能省：
 *
 * 1 解析失败重试一次
 *    模型偶尔会漏掉 JSON 包裹。重试时把「只输出 JSON」再说一遍，
 *    比直接降级划算——降级意味着采购员白说一遍。
 *
 * 2 丢弃模板里没有的 key
 *    模型会热心地多给几个字段（比如自己加个 "supplier_type"）。
 *    照单全收，这些字段就会静默消失（数据库没地方放），
 *    不如在这里就明确丢掉。
 *
 * 3 忠实性校验：basis 必须是原文子串
 *    这是唯一能自动发现"模型编了依据"的手段。
 *    编出来的依据比没有依据更危险——它看起来很有说服力。
 *
 * 4 彻底失败就降级，不要抛异常
 *    降级后的结果是"全空"，下游会判成不合规并要求补录——
 *    这比一个 500 页面有用得多。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Extractor {

    private final LlmClient llmClient;
    private final PromptBuilder promptBuilder;
    private final ObjectMapper objectMapper;

    public ExtractionResult extract(TemplateSchema schema, String dialogue, LocalDate today) {
        String systemPrompt = promptBuilder.systemPrompt(schema, today);

        for (int attempt = 1; attempt <= 2; attempt++) {
            String userContent = attempt == 1
                    ? dialogue
                    : dialogue + "\n\n（上一次的输出无法解析。请只输出 JSON 对象，不要任何其他文字。）";
            try {
                String raw = llmClient.complete(systemPrompt, userContent);
                ExtractionResult result = parse(raw, schema, dialogue);
                result.setAttempts(attempt);
                return result;
            } catch (Exception e) {
                log.warn("第 {} 次抽取失败：{}", attempt, e.getMessage());
            }
        }

        ExtractionResult degraded = new ExtractionResult();
        degraded.setAttempts(2);
        degraded.setDegradedReason("模型输出无法解析");
        log.error("抽取降级：两次都没能拿到可用 JSON，本次按全空处理");
        return degraded;
    }

    // ---------------------------------------------------------- 解析与校验

    ExtractionResult parse(String raw, TemplateSchema schema, String dialogue) throws Exception {
        JsonNode root = objectMapper.readTree(stripFence(raw));

        ExtractionResult result = new ExtractionResult();
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, String> basis = new LinkedHashMap<>();

        JsonNode valuesNode = root.path("values");
        JsonNode basisNode = root.path("basis");

        for (TemplateField field : schema.fields()) {
            JsonNode valueNode = valuesNode.path(field.getKey());
            Object value = toValue(valueNode);
            if (value == null) {
                continue;
            }
            values.put(field.getKey(), value);

            // 忠实性：依据必须是原文的一部分，否则宁可不要
            String quote = basisNode.path(field.getKey()).asText(null);
            if (quote != null && !quote.isBlank()) {
                String trimmed = quote.trim();
                result.setBasisGiven(result.getBasisGiven() + 1);
                if (dialogue.contains(trimmed)) {
                    basis.put(field.getKey(), trimmed);
                } else {
                    // 编造的依据比没有依据更危险：它看起来很有说服力。
                    // 这里不能只丢掉了事，要计数——否则永远不知道模型编了多少。
                    result.setBasisDropped(result.getBasisDropped() + 1);
                    log.debug("丢弃编造的依据 {}：{}", field.getKey(), trimmed);
                }
            }
        }

        result.setValues(values);
        result.setBasis(basis);
        String summary = root.path("summary").asText(null);
        result.setSummary(summary == null || summary.isBlank() ? null : summary.trim());
        return result;
    }

    /** 去掉可能出现的 ```json 围栏：模型偶尔不听话 */
    private String stripFence(String raw) {
        String text = raw.trim();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline > 0) {
                text = text.substring(firstNewline + 1);
            }
            int lastFence = text.lastIndexOf("```");
            if (lastFence >= 0) {
                text = text.substring(0, lastFence);
            }
        }
        return text.trim();
    }

    private Object toValue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        String text = node.asText();
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.trim();
        // 模型有时会把"不知道"写成这些词，等同于没填
        if (trimmed.equals("未知") || trimmed.equals("不详") || trimmed.equals("未提供")
                || trimmed.equals("null") || trimmed.equals("N/A")) {
            return null;
        }
        return trimmed;
    }
}
