package com.example.ticket.template.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 模板列表项。列表不返回 fields，避免把整份 schema 拖进列表页。
 */
@Data
public class TemplateSummary {

    private Long id;
    private String name;
    private Integer version;
    private Boolean enabled;
    private Integer fieldCount;
    private LocalDateTime updatedAt;
}
