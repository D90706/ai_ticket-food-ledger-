package com.example.ticket.extraction;

import com.example.ticket.template.FieldDef;
import com.example.ticket.template.FieldType;
import com.example.ticket.template.TemplateSchema;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 按 schema 校验模型输出。
 *
 * 只管「结构是否合法」，不管「信息够不够」：
 *   值本身是 null      → 合法（信息缺失，交核验层）
 *   值不符合字段定义    → 违规（重试可能修好）
 *
 * 这条边界很重要。如果把 null 也当成违规，就会因为「顾客没给订单号」
 * 而无限重试——那是模型修不好的事。
 *
 * 另一个刻意的边界：不校验 basis 是否为对话原文子串。
 * 那需要对话文本，属于忠实性检查，是核验层的职责（QualityEvaluator）。
 * 这里只要求「有值的字段必须给出依据」。
 */
@Component
public class JsonSchemaValidator {

    private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    public ValidationResult validate(TemplateSchema schema, JsonNode root) {
        List<Violation> violations = new ArrayList<>();

        JsonNode values = root.path("values");
        JsonNode basis = root.path("basis");

        if (!values.isObject()) {
            violations.add(Violation.of("VALUES_NOT_OBJECT", "values",
                    "values 必须是对象"));
            return new ValidationResult(violations);
        }
        if (!basis.isObject()) {
            violations.add(Violation.of("BASIS_NOT_OBJECT", "basis",
                    "basis 必须是对象"));
        }

        checkKeys(schema, values, violations);
        for (FieldDef field : schema.getFields()) {
            JsonNode value = values.path(field.getKey());
            if (value.isMissingNode() || value.isNull()) {
                continue;   // 缺失是合法的，不必再做类型检查
            }
            checkValue(field, value, violations);
            checkBasis(field, basis, violations);
        }

        checkSuggestion(schema, root.path("suggestion"), violations);
        checkSummary(root.path("summary"), violations);

        return new ValidationResult(violations);
    }

    // ---------------------------------------------------------- 结构

    private void checkKeys(TemplateSchema schema, JsonNode values, List<Violation> violations) {
        Set<String> expected = schema.keys();
        Set<String> actual = new LinkedHashSet<>();
        values.fieldNames().forEachRemaining(actual::add);

        for (String key : expected) {
            if (!actual.contains(key)) {
                violations.add(Violation.of("MISSING_KEY", key,
                        "values 缺少字段 " + key + "（即使是 null 也必须出现）"));
            }
        }
        for (String key : actual) {
            if (!expected.contains(key)) {
                violations.add(Violation.of("UNKNOWN_KEY", key,
                        "values 里出现了未定义的字段 " + key));
            }
        }
    }

    // ---------------------------------------------------------- 值

    private void checkValue(FieldDef field, JsonNode value, List<Violation> violations) {
        FieldType type = field.getType();
        if (type == null) {
            return;
        }
        String key = field.getKey();

        switch (type) {
            case IMAGE -> violations.add(Violation.of("IMAGE_NOT_NULL", key,
                    "image 字段必须为 null，你无法读取图片"));
            case TEXT -> {
                if (!value.isTextual()) {
                    violations.add(typeViolation(key, "text", value));
                    return;
                }
                if (field.hasPattern() && !matches(field.getPattern(), value.asText())) {
                    violations.add(Violation.of("PATTERN_MISMATCH", key,
                            "字段 " + key + " 的值「" + value.asText()
                                    + "」不符合格式 " + field.getPattern()));
                }
            }
            case ENUM -> {
                if (!value.isTextual()) {
                    violations.add(typeViolation(key, "enum", value));
                    return;
                }
                List<String> options = field.getOptions();
                if (options != null && !options.contains(value.asText())) {
                    violations.add(Violation.of("ENUM_OUT_OF_RANGE", key,
                            "字段 " + key + " 的值「" + value.asText()
                                    + "」不在候选内：" + String.join("/", options)));
                }
            }
            case NUMBER -> {
                if (!value.isNumber()) {
                    violations.add(typeViolation(key, "number", value));
                }
            }
            case BOOLEAN -> {
                if (!value.isBoolean()) {
                    violations.add(typeViolation(key, "boolean", value));
                }
            }
            case DATE -> {
                if (!value.isTextual() || !DATE.matcher(value.asText()).matches()) {
                    violations.add(Violation.of("DATE_FORMAT", key,
                            "字段 " + key + " 必须是 yyyy-MM-dd 格式"));
                    return;
                }
                try {
                    LocalDate.parse(value.asText());
                } catch (DateTimeParseException e) {
                    violations.add(Violation.of("DATE_INVALID", key,
                            "字段 " + key + " 的日期不存在：" + value.asText()));
                }
            }
        }
    }

    private Violation typeViolation(String key, String expected, JsonNode value) {
        return Violation.of("TYPE_MISMATCH", key,
                "字段 " + key + " 应为 " + expected + "，实际是 " + value.getNodeType());
    }

    private boolean matches(String pattern, String value) {
        try {
            return Pattern.compile(pattern).matcher(value).matches();
        } catch (PatternSyntaxException e) {
            // 模板里的正则非法，属于模板问题，不该怪模型
            return true;
        }
    }

    // ---------------------------------------------------------- 依据

    private void checkBasis(FieldDef field, JsonNode basis, List<Violation> violations) {
        JsonNode b = basis.path(field.getKey());
        if (b.isMissingNode() || b.isNull() || !b.isTextual() || b.asText().isBlank()) {
            violations.add(Violation.of("BASIS_MISSING", field.getKey(),
                    "字段 " + field.getKey() + " 有值，但缺少 basis 原文依据"));
        }
    }

    // ---------------------------------------------------------- 其它字段

    private void checkSuggestion(TemplateSchema schema, JsonNode suggestion,
                                 List<Violation> violations) {
        if (suggestion.isMissingNode() || suggestion.isNull()) {
            return;
        }
        if (!suggestion.isTextual()) {
            violations.add(Violation.of("SUGGESTION_TYPE", "suggestion",
                    "suggestion 必须是字符串或 null"));
            return;
        }
        if (!schema.getDecisionOptions().contains(suggestion.asText())) {
            violations.add(Violation.of("SUGGESTION_NOT_ALLOWED", "suggestion",
                    "suggestion「" + suggestion.asText() + "」不在决策选项内："
                            + String.join("/", schema.getDecisionOptions())));
        }
    }

    private void checkSummary(JsonNode summary, List<Violation> violations) {
        if (summary.isMissingNode() || summary.isNull()) {
            return;
        }
        if (!summary.isTextual()) {
            violations.add(Violation.of("SUMMARY_TYPE", "summary",
                    "summary 必须是字符串或 null"));
        }
    }
}
