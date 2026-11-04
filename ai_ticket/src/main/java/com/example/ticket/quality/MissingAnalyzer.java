package com.example.ticket.quality;

import com.example.ticket.template.FieldDef;
import com.example.ticket.template.TemplateSchema;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 缺失判定。纯集合运算，不看对话、不看模型输出。
 *
 * 一条容易踩错的语义：「空」不等于「假值」。
 *   null            → 缺失
 *   空字符串         → 缺失
 *   false / 0       → **不是缺失**，是明确的否定与零
 * 把 false 当缺失，会让「是否已穿着 = 否」这种正确答案
 * 被追着问第二遍。
 */
@Component
public class MissingAnalyzer {

    public MissingReport analyze(TemplateSchema schema, Map<String, Object> values) {
        List<String> required = new ArrayList<>();
        List<String> optional = new ArrayList<>();

        for (FieldDef field : schema.getFields()) {
            if (isMissing(values, field.getKey())) {
                (field.isRequired() ? required : optional).add(field.getKey());
            }
        }

        List<String> all = new ArrayList<>(required);
        all.addAll(optional);
        return new MissingReport(List.copyOf(required), List.copyOf(optional), List.copyOf(all));
    }

    public boolean isMissing(Map<String, Object> values, String key) {
        Object v = values == null ? null : values.get(key);
        if (v == null) {
            return true;
        }
        return v instanceof String s && s.isBlank();
    }
}
