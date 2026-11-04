package com.example.ticket.template.dto;

import com.example.ticket.template.FieldDef;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 模板详情响应。字段名必须与 mock/template.json 完全一致，
 * 前端就是按那个文件开发的。
 */
@Data
public class TemplateResponse {

    private Long id;
    private String name;
    private Integer version;
    private Boolean enabled;
    private String role;
    private String knowledge;
    private List<String> decisionOptions;
    private List<FieldDef> fields;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
