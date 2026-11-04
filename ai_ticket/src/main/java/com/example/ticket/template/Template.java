package com.example.ticket.template;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 模板实体。
 *
 * JSON 列（decision_options / fields）在这里一律用 String 承载，
 * 反序列化统一放在 TemplateService 里做。
 *
 * 这是对 design-modules.md 里 JacksonTypeHandler 方案的一处有意偏离：
 * 用 TypeHandler 需要 @TableName(autoResultMap = true)，
 * 忘记开就会在查询时抛 ClassCastException，而且每个 JSON 字段都要单独声明。
 * 对四张表的小项目来说，String + 集中在 service 转换更可控，
 * 代价只是模板读取时多一次 Jackson 解析（可忽略）。
 */
@Data
@TableName("template")
public class Template {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String name;

    private Integer version;

    @TableField("role_text")
    private String roleText;

    private String knowledge;

    @TableField("decision_options")
    private String decisionOptionsJson;

    @TableField("fields")
    private String fieldsJson;

    private Boolean enabled;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
