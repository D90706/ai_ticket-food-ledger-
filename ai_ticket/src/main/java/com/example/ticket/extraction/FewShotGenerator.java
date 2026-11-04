package com.example.ticket.extraction;

import com.example.ticket.common.BizException;
import com.example.ticket.template.FieldDef;
import com.example.ticket.template.FieldType;
import com.example.ticket.template.TemplateSchema;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 按 schema 自动生成 few-shot 示例。
 *
 * 这是「通用」能不付准确率代价的技术前提：
 * 字段由用户定义，开发者无法为每个模板手写示例，
 * 所以示例必须机械生成。
 *
 * 生成规则（每种类型取一个合法值）：
 *   text    有 pattern 时尽力生成一个能匹配的值，否则用「示例文本」
 *   enum    取第一个候选
 *   number  1
 *   boolean false
 *   date    固定日期
 *   image   null（AI 不处理图片）
 *
 * 同时合成一句示例对话，让每个非空值的 basis 都是这句话的原文片段。
 * 这样示例本身就在示范「依据必须逐字摘自对话」。
 */
@Component
@RequiredArgsConstructor
public class FewShotGenerator {

    private static final String SAMPLE_DATE = "2026-10-01";

    private static final List<String> TEXT_CANDIDATES =
            List.of("12345678", "20261008", "A1", "ABC123", "1");

    private static final Pattern ALTERNATION =
            Pattern.compile("^\\^\\((.+)\\)\\$");

    private final ObjectMapper mapper;

    public FewShot generate(TemplateSchema schema) {
        List<String> sentences = new ArrayList<>();
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, String> basis = new LinkedHashMap<>();

        for (FieldDef field : schema.getFields()) {
            Object value = sampleValue(field);
            values.put(field.getKey(), value);
            if (value == null) {
                basis.put(field.getKey(), null);
                continue;
            }
            String sentence = sentenceOf(field, value);
            sentences.add(sentence);
            basis.put(field.getKey(), sentence);
        }

        String dialogue = sentences.isEmpty() ? "（无可用字段）" : String.join("，", sentences) + "。";

        Map<String, Object> example = new LinkedHashMap<>();
        example.put("values", values);
        example.put("basis", basis);
        example.put("suggestion", schema.getDecisionOptions().isEmpty()
                ? null : schema.getDecisionOptions().get(0));
        example.put("summary", "示例：用户提出了一个明确诉求，关键信息已给出。");

        try {
            return new FewShot(dialogue,
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(example));
        } catch (Exception e) {
            throw new BizException(500, "生成 few-shot 示例失败: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------- 取值

    private Object sampleValue(FieldDef field) {
        FieldType type = field.getType();
        if (type == null) {
            return null;
        }
        return switch (type) {
            case TEXT -> sampleText(field);
            case ENUM -> {
                List<String> options = field.getOptions();
                yield (options == null || options.isEmpty()) ? null : options.get(0);
            }
            case NUMBER -> 1;
            case BOOLEAN -> Boolean.FALSE;
            case DATE -> SAMPLE_DATE;
            case IMAGE -> null;
        };
    }

    /**
     * 尽力生成一个满足 pattern 的文本。
     * 优先用候选值试，试不出来再从「^(A|B|C)$」这类简单枚举正则里取第一个分支。
     * 都不行就退回「示例文本」——示例允许不完美，但不能因此失败。
     */
    private String sampleText(FieldDef field) {
        if (!field.hasPattern()) {
            return "示例文本";
        }
        Pattern pattern;
        try {
            pattern = Pattern.compile(field.getPattern());
        } catch (Exception e) {
            return "示例文本";
        }
        for (String candidate : TEXT_CANDIDATES) {
            if (pattern.matcher(candidate).matches()) {
                return candidate;
            }
        }
        String fromAlternation = firstAlternation(field.getPattern());
        if (fromAlternation != null && pattern.matcher(fromAlternation).matches()) {
            return fromAlternation;
        }
        return "示例文本";
    }

    private String firstAlternation(String pattern) {
        Matcher m = ALTERNATION.matcher(pattern);
        if (!m.matches()) {
            return null;
        }
        String[] branches = m.group(1).split("\\|");
        return branches.length == 0 ? null : branches[0].trim();
    }

    // ---------------------------------------------------------- 造句

    private String sentenceOf(FieldDef field, Object value) {
        String label = field.getLabel() == null ? field.getKey() : field.getLabel();
        if (field.getType() == FieldType.BOOLEAN) {
            boolean flag = Boolean.TRUE.equals(value);
            return label + "：" + (flag ? "是" : "否");
        }
        return label + "：" + value;
    }
}
