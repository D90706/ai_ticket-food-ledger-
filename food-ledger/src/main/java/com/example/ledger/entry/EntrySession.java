package com.example.ledger.entry;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 录入会话。
 *
 * 状态：
 *   CHATTING         还在问
 *   AWAITING_CONFIRM 信息不全，已备好草稿，等采购员确认
 *   RECORDED         已生成台账记录（状态是「待入账」，不是「已入账」）
 */
@Data
@TableName("entry_session")
public class EntrySession {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long templateId;

    private Integer templateVersion;

    private String state;

    private Integer followupCount;

    @TableField("collected_values")
    private String collectedValues;

    @TableField("collected_basis")
    private String collectedBasis;

    private String draftSummary;

    private String lastNotifiedStatus;

    private Long recordId;

    private String createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}