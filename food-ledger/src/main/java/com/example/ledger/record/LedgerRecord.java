package com.example.ledger.record;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 采购台账记录：一行 = 一个品项。
 *
 * 列分成四组，看的时候别混：
 *   1 法定项目（法律固定，所以要能查能算，必须是列）
 *   2 自定义字段（extra_json，模板声明，SQL 不关心）
 *   3 我们的想法（quality_* / basis / source_*）
 *   4 审计与并发（version / *_by / revision）
 */
@Data
@TableName("ledger_record")
public class LedgerRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    // ---------------- 法定项目 ----------------
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

    // ---------------- 索证索票 ----------------
    private String certificate;

    @TableField("certificate_ok")
    private Boolean certificateOk;

    /** JSON 数组：索证索票照片等附件 */
    private String attachments;

    // ---------------- 统计与预警 ----------------
    private LocalDate expiryDate;
    private Long categoryId;
    private Long supplierId;
    private BigDecimal unitPrice;
    private BigDecimal amount;
    private String warehouse;
    private String receiver;
    private String remark;

    // ---------------- 自定义字段与模板快照 ----------------
    @TableField("extra_json")
    private String extraJson;

    private Long templateId;
    private Integer templateVersion;

    @TableField("schema_snapshot")
    private String schemaSnapshot;

    // ---------------- 我们的想法 ----------------
    private String qualityLevel;

    @TableField("quality_detail")
    private String qualityDetail;

    private String basis;

    /** 一句话说明：人工填写时可空，AI 录入时是模型概括的那句 */
    private String summary;

    private String status;

    private String sourceType;

    private Long sourceSessionId;

    // ---------------- 修订与打回 ----------------
    private Integer revision;

    private Integer returnCount;

    private String returnNote;

    // ---------------- 审计与并发 ----------------
    @Version
    private Integer version;

    private String createdBy;

    private String lastActor;

    private String postedBy;

    private LocalDateTime postedAt;

    private String voidReason;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}