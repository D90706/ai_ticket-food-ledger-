package com.example.ticket.chat;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话实体。JSON 列同样用 String 承载，转换在 DbSessionStore 里。
 */
@Data
@TableName("chat_session")
public class ChatSession {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long templateId;

    private Integer templateVersion;

    private String state;

    private Integer followupCount;

    @TableField("collected_values")
    private String collectedValuesJson;

    @TableField("collected_basis")
    private String collectedBasisJson;

    /** 待确认时暂存的一句话摘要（顾客可改） */
    @TableField("draft_summary")
    private String draftSummary;

    /** 已经通知过顾客的工单状态，用于幂等回执 */
    @TableField("last_notified_status")
    private String lastNotifiedStatus;

    private Long ticketId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
