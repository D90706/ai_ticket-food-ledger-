package com.example.ticket.template;

import lombok.Getter;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 模板的运行时视图。
 *
 * 这是抽取层与核验层看到的唯一类型——它们不认识 Template 实体，
 * 只认识 TemplateSchema。这样做有两个好处：
 *   1. 抽取层不需要知道 JSON 列怎么存、怎么反序列化；
 *   2. 核验层可以纯函数化，拿它当不可变输入，完全不依赖数据库。
 *
 * 因此这里把「按 schema 自省」的能力收拢成方法：
 * requiredFields / optionalFields / find / keys，
 * 后面的 MissingAnalyzer 与 QualityEvaluator 全靠这几个方法。
 */
@Getter
public class TemplateSchema {

    private final String name;
    private final int version;
    private final String role;
    private final String knowledge;
    private final List<String> decisionOptions;
    private final List<FieldDef> fields;

    public TemplateSchema(String name, int version, String role, String knowledge,
                          List<String> decisionOptions, List<FieldDef> fields) {
        this.name = name;
        this.version = version;
        this.role = role;
        this.knowledge = knowledge;
        this.decisionOptions = decisionOptions == null
                ? List.of() : List.copyOf(decisionOptions);
        this.fields = fields == null ? List.of() : List.copyOf(fields);
    }

    public List<FieldDef> requiredFields() {
        return fields.stream().filter(FieldDef::isRequired).toList();
    }

    public List<FieldDef> optionalFields() {
        return fields.stream().filter(f -> !f.isRequired()).toList();
    }

    public Optional<FieldDef> find(String key) {
        return fields.stream().filter(f -> f.getKey().equals(key)).findFirst();
    }

    public Set<String> keys() {
        Set<String> set = new LinkedHashSet<>();
        fields.forEach(f -> set.add(f.getKey()));
        return Collections.unmodifiableSet(set);
    }

    public int fieldCount() {
        return fields.size();
    }
}
