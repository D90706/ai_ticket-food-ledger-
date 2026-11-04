package com.example.ticket.template.dto;

import lombok.Data;

/**
 * 面向用户端的模板选项：只有 id 和名称。
 *
 * 为什么不直接复用 TemplateSummary：
 * 那里面带字段数、版本、更新时间——对顾客毫无意义，
 * 而且会把后台配置的轮廓暴露给用户端。
 * 用户端只需要知道「这里能咨询哪几类问题」。
 */
@Data
public class TemplateOption {

    private Long id;
    private String name;
}
