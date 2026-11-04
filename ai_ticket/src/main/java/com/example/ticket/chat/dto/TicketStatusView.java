package com.example.ticket.chat.dto;

import lombok.Data;

/**
 * 顾客端能看到的工单状态回执。
 *
 * 只回四样东西：工单号、状态、处理结果、一句人话。
 * 不回 values / basis / qualityDetail——
 * 那些是人工端的判断依据，顾客不需要也不该看到。
 */
@Data
public class TicketStatusView {

    private Long ticketId;

    /** PENDING / INFO_INCOMPLETE / ASSIGNED / PROCESSING / RESOLVED / CLOSED */
    private String status;

    /** 人工填的处理结果，可能为空 */
    private String decision;

    /**
     * 给顾客看的一句话回执。
     * 为 null 表示这次没有新消息（状态没变化，或还没到该通知的节点）。
     */
    private String notice;

    /** 这次请求是否产生了新回执（前端据此决定要不要追加气泡） */
    private boolean changed;
}
