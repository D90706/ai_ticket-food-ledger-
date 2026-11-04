package com.example.ledger.template;

import java.util.List;

/**
 * 渲染与判定用的模板视图。
 *
 * 台账记录会把它整份存进 schema_snapshot：
 * 以后模板改了，历史记录仍按当时那版渲染与判定。
 */
public record TemplateSchema(
        Long templateId,
        String name,
        int version,
        List<TemplateField> fields,
        int shelfLifeWarnDays) {

    public List<TemplateField> requiredFields() {
        return fields.stream().filter(TemplateField::isRequired).toList();
    }

    public List<TemplateField> legalFields() {
        return fields.stream().filter(TemplateField::isFixed).toList();
    }

    public List<TemplateField> customFields() {
        return fields.stream().filter(f -> !f.isFixed()).toList();
    }

    public TemplateField field(String key) {
        return fields.stream().filter(f -> f.getKey().equals(key)).findFirst().orElse(null);
    }
}