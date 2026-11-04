package com.example.ledger.template;

import lombok.Data;

import java.util.List;

/**
 * 模板里的一个字段定义。
 *
 * fixed = true 表示法定项目（《食品安全法》第 53 条的进货查验记录项目），
 * 前端据此禁止删除；fixed = false 是单位自定义字段，值存进 extra_json。
 */
@Data
public class TemplateField {

    private String key;

    private String label;

    /** text / number / date / enum / boolean / image */
    private String type;

    private boolean required;

    /** 法定项目 */
    private boolean fixed;

    private List<String> options;

    private String hint;
}