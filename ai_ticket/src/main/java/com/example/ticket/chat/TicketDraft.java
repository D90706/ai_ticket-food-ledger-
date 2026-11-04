package com.example.ticket.chat;

import com.example.ticket.quality.QualityReport;
import com.example.ticket.template.FieldDef;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 待落库的工单草稿。
 *
 * 编排层只负责「判断该出单了，并把材料备齐」；
 * 真正写库、生成 id、走状态机，是模块 6（TicketFactory）的事。
 * 这条边界让编排层可以在没有数据库的情况下被完整测试。
 *
 * schemaSnapshot 必须随草稿一起带走：
 * 工单渲染永远用生成那一刻的 schema，不回查当前模板。
 */
@Data
public class TicketDraft {

    private Long sessionId;
    private Long templateId;
    private int templateVersion;

    /** schema 快照 */
    private List<FieldDef> fields;
    private List<String> decisionOptions;

    private Map<String, Object> values;
    private Map<String, String> basis;
    private String summary;

    /** 缺失必填时会被强制置空（不采用模型建议） */
    private String suggestion;

    private QualityReport quality;

    /** PENDING 或 INFO_INCOMPLETE */
    private String status;

    /**
     * 顾客是否在提交前确认过内容。
     * 记下来不是为了放松分级，而是为了让「确认这一步到底有没有用」可被验证：
     * 对比确认过与未确认的工单，人工抽查时内容不符的比例有没有下降。
     */
    private boolean customerConfirmed;

    public boolean infoIncomplete() {
        return "INFO_INCOMPLETE".equals(status);
    }
}
