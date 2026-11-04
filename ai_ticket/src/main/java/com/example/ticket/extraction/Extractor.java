package com.example.ticket.extraction;

import com.example.ticket.llm.LlmClient;
import com.example.ticket.llm.LlmOptions;
import com.example.ticket.llm.LlmResponse;
import com.example.ticket.llm.Message;
import com.example.ticket.template.FieldDef;
import com.example.ticket.template.TemplateSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 抽取层的总装：模板 + 对话 → 四字段结果。
 *
 * 循环结构只有一件事要理解：
 *   调用 → 解析 → 校验 → 有违规则带着纠错提示重试 → 耗尽则降级。
 *
 * 三条边界（design-modules.md §七）：
 *   不判断缺失（那是核验层）
 *   不评分、不分级（那是核验层）
 *   不落库、不决定追问（那是编排层）
 *
 * 它唯一的产出是一份「结构合法的四字段结果」，或者一份诚实的降级结果。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Extractor {

    private final PromptBuilder promptBuilder;
    private final LlmClient llmClient;
    private final JsonOutputParser parser;
    private final JsonSchemaValidator validator;
    private final RetryPolicy retryPolicy;
    private final ObjectMapper mapper;

    public ExtractionResult extract(TemplateSchema schema, String dialogue) {
        List<Message> base = promptBuilder.build(schema, dialogue);

        String lastRaw = null;
        ValidationResult last = ValidationResult.ok();
        int attempts = 0;

        while (attempts < retryPolicy.maxAttempts()) {
            attempts++;

            List<Message> messages = new ArrayList<>(base);
            if (attempts > 1) {
                // 带上下文重试：把上一次的错误输出与违规项一起反馈，
                // 比重新问一遍有效得多。
                messages.add(Message.assistant(lastRaw == null ? "" : lastRaw));
                messages.add(Message.user(correction(last)));
            }

            LlmResponse response = llmClient.chat(messages, LlmOptions.defaults());
            lastRaw = response.content();

            JsonOutputParser.ParseResult parsed = parser.parse(lastRaw);
            if (!parsed.success()) {
                last = new ValidationResult(List.of(
                        Violation.of("JSON_PARSE", null, parsed.error())));
            } else {
                last = validator.validate(schema, parsed.node());
                if (last.isValid()) {
                    log.debug("抽取成功 尝试={} 耗时={}ms", attempts, response.latencyMs());
                    return build(schema, parsed.node(), lastRaw, attempts);
                }
            }

            log.warn("抽取第 {} 次有 {} 项结构违规: {}",
                    attempts, last.violations().size(), last.violations());

            if (!retryPolicy.shouldRetry(attempts, last)) {
                break;
            }
        }

        log.warn("抽取重试耗尽，降级为全员缺失（尝试 {} 次）", attempts);
        return ExtractionResult.degradedOf(schema, lastRaw, attempts, last.violations());
    }

    // ---------------------------------------------------------- 结果组装

    private ExtractionResult build(TemplateSchema schema, JsonNode root, String raw, int attempts) {
        JsonNode valuesNode = root.path("values");
        JsonNode basisNode = root.path("basis");

        ExtractionResult result = new ExtractionResult();
        result.setRawContent(raw);
        result.setAttempts(attempts);

        // 键一律以 schema 为准，缺的补 null ——
        // 下游 MissingAnalyzer 依赖「键是否齐全」来判断缺失
        for (FieldDef field : schema.getFields()) {
            result.getValues().put(field.getKey(), toJava(valuesNode.path(field.getKey())));
            JsonNode b = basisNode.path(field.getKey());
            result.getBasis().put(field.getKey(),
                    (b.isMissingNode() || b.isNull() || !b.isTextual()) ? null : b.asText());
        }

        result.setSuggestion(textOrNull(root.path("suggestion")));
        result.setSummary(textOrNull(root.path("summary")));
        result.setViolations(List.of());
        result.setDegraded(false);
        return result;
    }

    private Object toJava(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return mapper.convertValue(node, Object.class);
    }

    private String textOrNull(JsonNode node) {
        return (node == null || node.isMissingNode() || node.isNull() || !node.isTextual())
                ? null : node.asText();
    }

    // ---------------------------------------------------------- 纠错提示

    private String correction(ValidationResult result) {
        return """
                你上一次的输出有以下问题，请修正后重新输出：
                %s
                只输出一个 JSON 对象，不要解释。若某个值确实无法从对话中确定，
                就填 null —— null 是合法答案，编造不是。
                """.formatted(result.describe());
    }
}
