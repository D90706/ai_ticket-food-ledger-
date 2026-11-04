package com.example.ledger.record.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 台账列表行。只带列表要用的字段，别把整条记录塞给表格。 */
@Data
public class LedgerView {

    private Long id;
    private String ingredientName;
    private String spec;
    private BigDecimal quantity;
    private String unit;
    private String batchNo;
    /** 导出用：法定项目要能直接进 Excel，不能只靠详情页 */
    private LocalDate productionDate;
    private String shelfLife;
    private String supplierAddress;
    private String supplierPhone;
    private String certificate;
    private LocalDate purchaseDate;
    private LocalDate expiryDate;
    private String supplierName;
    private Long categoryId;
    private String categoryName;
    private BigDecimal unitPrice;
    private BigDecimal amount;
    private String warehouse;
    private String receiver;

    /** 一句话说明（AI 录入时有值） */
    private String summary;

    /** RED / YELLOW / GREEN 与对应中文，前端不该自己映射 */
    private String qualityLevel;
    private String qualityLabel;

    private String status;
    private String statusLabel;

    private Integer returnCount;
    private Integer revision;
    private String sourceType;

    /** 距到期天数：负数表示已过期；null 表示没有到期日 */
    private Long expiryDaysLeft;

    private String createdBy;
    private LocalDateTime createdAt;
}