package com.example.ticket.ticket.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 工单详情。
 *
 * schemaSnapshot 与 qualityDetail 都以结构化对象返回，
 * 前端渲染工单时用它，永远不使用模板的当前版本。
 */
@Data
public class TicketDetail {

    private Long id;
    private Long templateId;
    private Integer templateVersion;

    private Map<String, Object> schemaSnapshot;

    private Map<String, Object> values;
    private Map<String, String> basis;
    private String summary;
    private String suggestion;

    private String qualityLevel;
    private Map<String, Object> qualityDetail;

    private String status;
    private String assignee;
    private String decision;

    private Long sessionId;
    private Boolean needsClarify;

    /** 顾客是否在入队前确认过内容 */
    private Boolean customerConfirmed;

    /** 乐观锁版本：前端保存时必须原样带回来 */
    private Integer version;

    /** 最后一次修改者 */
    private String lastActor;

    /** 内容修订号：顾客每次补全 +1 */
    private Integer revision;

    /** 被打回次数 */
    private Integer returnCount;

    /** 打回时人工留的话 */
    private String returnNote;
    private Integer decisionTimeMs;

    private LocalDateTime createdAt;
    private LocalDateTime decidedAt;
}
