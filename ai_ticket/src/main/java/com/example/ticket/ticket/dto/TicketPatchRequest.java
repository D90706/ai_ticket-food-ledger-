package com.example.ticket.ticket.dto;

import lombok.Data;

/**
 * 工单更新请求。
 *
 * 只允许这四个字段，其它一律忽略。
 * 注意 needsClarify 是「人工是否还需回问顾客」——
 * 它是回问率的埋点，不是业务状态。
 */
@Data
public class TicketPatchRequest {

    /**
     * 客户端看到的版本号。必填。
     *
     * 不给默认值、不做「没传就宽松通过」：
     * 静默丢更新比报错难查一百倍。
     */
    private Integer version;

    private String status;
    private String assignee;
    private String decision;
    private Boolean needsClarify;

    /**
     * 打回时给顾客留的话（可空）。
     * 空的时候由系统按缺失字段自动生成追问。
     */
    private String returnNote;
}
