package com.example.ticket.ticket;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 工单状态机。固定流转，不做可视化编排。
 *
 * 七个状态。INFO_INCOMPLETE 是「追问到上限仍缺必填」时的初始状态；
 * RETURNED 是「人工打回，等顾客补全」——它不是终结，是回到顾客那边的一轮。
 * 它不是错误状态——它是一张诚实的工单：
 * 信息不全，但明确告诉人工缺什么。
 *
 * 为什么把合法流转写成表而不是 if-else：
 * 状态机是最容易被随手加分支的地方，
 * 一张表能让「谁能到谁」这件事一眼可查、可测。
 */
public enum TicketStatus {

    PENDING,
    INFO_INCOMPLETE,
    ASSIGNED,
    PROCESSING,
    /** 人工打回：工单回到顾客那边补全，补完后重新进入 PENDING */
    RETURNED,
    RESOLVED,
    CLOSED;

    private static final Map<TicketStatus, Set<TicketStatus>> TRANSITIONS =
            new EnumMap<>(TicketStatus.class);

    static {
        // 待处理：可以指派、可以直接开始处理、也可以直接解决/关闭
        TRANSITIONS.put(PENDING, EnumSet.of(ASSIGNED, PROCESSING, RETURNED, RESOLVED, CLOSED));
        // 信息不全：和待处理一样能走完全程——人工补齐信息后照常处理
        TRANSITIONS.put(INFO_INCOMPLETE, EnumSet.of(PENDING, ASSIGNED, PROCESSING,
                RETURNED, RESOLVED, CLOSED));
        // 已指派 / 处理中都可以打回：人工发现自己处理不了、必须问顾客
        TRANSITIONS.put(ASSIGNED, EnumSet.of(PENDING, PROCESSING, RETURNED, RESOLVED, CLOSED));
        TRANSITIONS.put(PROCESSING, EnumSet.of(PENDING, RETURNED, RESOLVED, CLOSED));
        // 打回之后只有一条出路：顾客补全 → 重新排队（PENDING）。
        // 也允许人工自己收回（直接关闭），但不能再凭空解决——
        // 信息不全的单子不该被"标记为已解决"了事。
        TRANSITIONS.put(RETURNED, EnumSet.of(PENDING, CLOSED));
        // 已解决仍可关闭，也可以驳回重开
        TRANSITIONS.put(RESOLVED, EnumSet.of(PENDING, CLOSED));
        // 已关闭是终态，只允许重开
        TRANSITIONS.put(CLOSED, EnumSet.of(PENDING));
    }

    public boolean canTransitionTo(TicketStatus next) {
        return next != null && TRANSITIONS.getOrDefault(this, Set.of()).contains(next);
    }

    public Set<TicketStatus> nextStates() {
        return Set.copyOf(TRANSITIONS.getOrDefault(this, Set.of()));
    }

    /** 已决（用于判断是否要写 decided_at） */
    public boolean isDecided() {
        return this == RESOLVED || this == CLOSED;
    }

    public boolean isTerminal() {
        return this == CLOSED;
    }
}
