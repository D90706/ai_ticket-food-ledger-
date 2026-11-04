package com.example.ticket.chat.dto;

import lombok.Data;

import java.util.List;

/**
 * 一轮对话的响应。
 *
 * replyType 三种，前端按它 switch：
 *   ASK     → 显示 question，继续输入
 *   CONFIRM → 显示 summary（可编辑）与还缺什么，等顾客确认
 *   TICKET  → 显示工单号
 *
 * summary 与 missing 只在 CONFIRM 时有值。
 * 它们是给人读的：一句话 + 「还缺：订单号」，不是字段表。
 */
@Data
public class ChatResponse {

    private String replyType;

    /** ASK 时有值 */
    private String question;
    private Integer followupCount;

    /** CONFIRM 时有值 */
    private String summary;
    private List<String> missing;

    /** TICKET 时有值 */
    private Long ticketId;
    private String level;

    /** TICKET 时给人看的原因句子 */
    private List<String> messages;

    /** TICKET 时：是打回后补全重新提交，而不是新生成的工单 */
    private Boolean resubmitted;
}
