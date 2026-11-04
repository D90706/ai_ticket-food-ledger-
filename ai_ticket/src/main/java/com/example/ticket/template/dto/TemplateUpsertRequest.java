package com.example.ticket.template.dto;

import com.example.ticket.template.FieldDef;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * 模板整体提交请求。
 *
 * 注意：这是「整体提交」而不是「字段级 CRUD」——
 * 前端维护一份本地 json，改完整体 PUT。
 */
@Data
public class TemplateUpsertRequest {

    @NotBlank(message = "不能为空")
    private String name;

    @NotBlank(message = "不能为空")
    private String role;

    private String knowledge;

    private List<String> decisionOptions;

    private List<FieldDef> fields;
}
