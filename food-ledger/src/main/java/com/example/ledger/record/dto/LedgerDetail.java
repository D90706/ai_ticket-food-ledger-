package com.example.ledger.record.dto;

import com.example.ledger.common.Attachment;
import com.example.ledger.record.LedgerQuality;
import com.example.ledger.template.TemplateField;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 台账详情：列表字段 + 全部业务字段 + 判定明细 + 模板快照。 */
@Data
public class LedgerDetail {

    private Long id;

    // 业务字段
    private String ingredientName;
    private String spec;
    private BigDecimal quantity;
    private String unit;
    private LocalDate productionDate;
    private String batchNo;
    private String shelfLife;
    private LocalDate purchaseDate;
    private String supplierName;
    private String supplierAddress;
    private String supplierPhone;
    private String certificate;
    private Boolean certificateOk;
    private List<Attachment> attachments;
    private LocalDate expiryDate;
    private Long categoryId;
    private String categoryName;
    private Long supplierId;
    private BigDecimal unitPrice;
    private BigDecimal amount;
    private String warehouse;
    private String receiver;
    private String remark;
    private Map<String, Object> extra;
    /** 一句话说明（AI 录入时有值） */
    private String summary;

    // 判定结果：结论 + 原因 + 提示
    private String qualityLevel;
    private String qualityLabel;
    private LedgerQuality quality;
    private List<String> warnings;

    // 状态与审计
    private String status;
    private String statusLabel;
    private String sourceType;
    private Long sourceSessionId;
    private Integer revision;
    private Integer returnCount;
    private String returnNote;
    private Integer version;
    private String createdBy;
    private String lastActor;
    private String postedBy;
    private LocalDateTime postedAt;
    private String voidReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // 渲染依据：记录自己那版 schema，永不回查当前模板
    private Integer templateVersion;
    private List<TemplateField> fields;

    /** 字段的原文依据（AI 录入时有值）：字段 key → 原文片段 */
    private Map<String, String> basis;
}