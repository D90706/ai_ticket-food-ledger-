package com.example.ticket.chat;

/**
 * 会话状态。
 *
 * CHATTING          还在对话中，可以继续追问
 * AWAITING_CONFIRM  信息不全（追问已达上限），等顾客确认后才入队
 * TICKETED          已出单，不允许再往这个会话里发消息
 *
 * 为什么要有 AWAITING_CONFIRM：
 *   红级工单等于「信息不全，人工还得回头问顾客」。
 *   在它进入人工队列之前让顾客看一眼、可以改一句话，
 *   是我们唯一能拿到「语义正确性」信号的渠道——
 *   basis 校验只能证明引用没编造，防不住误读。
 */
public enum SessionState {

    /** 还在对话中，可以继续追问 */
    CHATTING,

    /** 已备好工单，等顾客确认（只对信息不全的工单启用） */
    AWAITING_CONFIRM,

    /** 已出单，不允许再往这个会话里发消息 */
    TICKETED
}
