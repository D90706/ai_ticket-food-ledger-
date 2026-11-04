package com.example.ticket.ticket;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工单实体。
 *
 * 三个必须记住的列：
 *   schemaSnapshot  渲染唯一依据，永不回查当前模板
 *   qualityDetail   三维度明细，不只是结论
 *   needsClarify    人工点「需联系顾客」，即回问率的埋点
 */
@Data
@TableName("ticket")
public class Ticket {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long templateId;

    private Integer templateVersion;

    @TableField("schema_snapshot")
    private String schemaSnapshotJson;

    @TableField("values_data")
    private String valuesJson;

    private String basis;

    private String summary;

    private String suggestion;

    private String qualityLevel;

    @TableField("quality_detail")
    private String qualityDetailJson;

    private String status;

    private String assignee;

    private String decision;

    private Long sessionId;

    /** 顾客是否在入队前确认过内容 */
    @TableField("customer_confirmed")
    private Boolean customerConfirmed;

    @TableField("needs_clarify")
    private Boolean needsClarify;

    @TableField("decision_time_ms")
    private Integer decisionTimeMs;

    /**
     * 乐观锁版本。updateById 时 MyBatis-Plus 会自动加上
     * SET version = version + 1 WHERE id = ? AND version = ?，
     * 影响行数为 0 就说明读出来之后被人改过。
     */
    @Version
    private Integer version;

    /** 最后一次修改者（登录客服的显示名），审计用 */
    @TableField("last_actor")
    private String lastActor;

    /** 内容修订号：打回补全后 +1，用来区分「第几版内容」 */
    private Integer revision;

    /** 被打回次数，用于封顶（同一张单不该无限来回踢） */
    @TableField("return_count")
    private Integer returnCount;

    /** 打回时人工给顾客留的话 */
    @TableField("return_note")
    private String returnNote;

    private LocalDateTime createdAt;

    private LocalDateTime decidedAt;
}
