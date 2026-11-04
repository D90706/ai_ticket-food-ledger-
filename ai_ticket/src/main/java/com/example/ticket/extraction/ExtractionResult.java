package com.example.ticket.extraction;

import com.example.ticket.template.FieldDef;
import com.example.ticket.template.TemplateSchema;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 抽取层的输出。字段名与 mock/extraction-result.json 一致。
 *
 * 四个业务字段之外，还有三个诊断字段（rawContent / attempts / degraded），
 * 它们不参与业务判断，只用于排查「这次为什么不对」。
 *
 * 一条不变量：values 里必须出现 schema 的**全部**字段 key，
 * 缺失的一律补 null。因为下游 MissingAnalyzer 靠「键是否存在」
 * 来区分「没抽到」和「schema 里没这个字段」，
 * 键缺失会让它算错。
 */
@Data
public class ExtractionResult {

    private Map<String, Object> values = new LinkedHashMap<>();
    private Map<String, String> basis = new LinkedHashMap<>();
    private String suggestion;
    private String summary;

    /** 模型最后一次的原始输出，排查用 */
    private String rawContent;

    /** 实际调用了几次（1 表示一次成功） */
    private int attempts;

    /** 是否因重试耗尽而降级（降级时 values 全为 null） */
    private boolean degraded;

    /** 最后一次的违规项，降级时用于解释原因 */
    private List<Violation> violations = List.of();

    public boolean hasValue(String key) {
        return values.get(key) != null;
    }

    /**
     * 降级结果：所有字段为 null。
     *
     * 为什么降级成「全员缺失」而不是抛异常：
     * 因为下游能处理一张全空的工单（判红 + 标信息不全），
     * 但不能处理一个异常。把失败变成一个可展示的状态，
     * 比让用户看到 500 更符合这个系统的目的。
     */
    public static ExtractionResult degradedOf(TemplateSchema schema, String rawContent,
                                              int attempts, List<Violation> violations) {
        ExtractionResult result = new ExtractionResult();
        for (FieldDef field : schema.getFields()) {
            result.values.put(field.getKey(), null);
            result.basis.put(field.getKey(), null);
        }
        result.rawContent = rawContent;
        result.attempts = attempts;
        result.degraded = true;
        result.violations = violations == null ? List.of() : List.copyOf(violations);
        result.suggestion = null;
        result.summary = null;
        return result;
    }
}
