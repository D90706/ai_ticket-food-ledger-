package com.example.ledger.record.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import com.example.ledger.common.Attachment;

import java.util.List;
import java.util.Map;

/**
 * 录入/补录请求。
 *
 * version 只在修改时必填：不传就是"我不想覆盖别人刚做的修改"，
 * 后端拒绝，而不是默默按最后一次写入。
 */
@Data
public class LedgerUpsertRequest {

    private Integer version;

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

    /** 索证索票照片等附件 */
    private List<Attachment> attachments;
    private Boolean certificateOk;

    private LocalDate expiryDate;
    private Long categoryId;
    private Long supplierId;
    private BigDecimal unitPrice;
    private BigDecimal amount;
    private String warehouse;
    private String receiver;
    private String remark;

    /** 模板声明的自定义字段值 */
    private Map<String, Object> extra;
}