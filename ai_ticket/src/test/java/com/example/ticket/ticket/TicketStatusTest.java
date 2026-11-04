package com.example.ticket.ticket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 状态机的单元测试。
 *
 * 重点是两条：信息不全不是死路（能走完全程），已关闭是终态（只能重开）。
 * 这两条一旦写错，工单要么卡住、要么被误关后再也打不开。
 */
class TicketStatusTest {

    @Test
    @DisplayName("信息不全可以指派、处理、解决——它不是错误状态")
    void infoIncompleteCanFlow() {
        assertTrue(TicketStatus.INFO_INCOMPLETE.canTransitionTo(TicketStatus.ASSIGNED));
        assertTrue(TicketStatus.INFO_INCOMPLETE.canTransitionTo(TicketStatus.PROCESSING));
        assertTrue(TicketStatus.INFO_INCOMPLETE.canTransitionTo(TicketStatus.RESOLVED));
        assertTrue(TicketStatus.INFO_INCOMPLETE.canTransitionTo(TicketStatus.CLOSED));
    }

    @Test
    @DisplayName("已关闭是终态，只允许重开")
    void closedIsTerminalExceptReopen() {
        assertTrue(TicketStatus.CLOSED.isTerminal());
        assertTrue(TicketStatus.CLOSED.canTransitionTo(TicketStatus.PENDING));
        assertFalse(TicketStatus.CLOSED.canTransitionTo(TicketStatus.PROCESSING));
        assertFalse(TicketStatus.CLOSED.canTransitionTo(TicketStatus.RESOLVED));
    }

    @Test
    @DisplayName("已解决可以关闭，也可以驳回重开")
    void resolvedCanCloseOrReopen() {
        assertTrue(TicketStatus.RESOLVED.canTransitionTo(TicketStatus.CLOSED));
        assertTrue(TicketStatus.RESOLVED.canTransitionTo(TicketStatus.PENDING));
        assertFalse(TicketStatus.RESOLVED.canTransitionTo(TicketStatus.ASSIGNED));
    }

    @Test
    @DisplayName("待处理可以直接指派或直接解决（不用强行两步走）")
    void pendingIsFlexible() {
        // 5 = 指派 / 处理中 / 打回补全 / 已解决 / 已关闭
        assertEquals(5, TicketStatus.PENDING.nextStates().size());
        assertTrue(TicketStatus.PENDING.canTransitionTo(TicketStatus.ASSIGNED));
        assertTrue(TicketStatus.PENDING.canTransitionTo(TicketStatus.RESOLVED));
        assertTrue(TicketStatus.PENDING.canTransitionTo(TicketStatus.RETURNED));
        // 但不允许等价停留之外的「信息不全」——那是生成时的初始状态，不该被手动设置
        assertFalse(TicketStatus.PENDING.canTransitionTo(TicketStatus.INFO_INCOMPLETE));
    }

    @Test
    @DisplayName("打回之后只有两条路：顾客补全重新排队，或人工直接关闭")
    void returnedIsABridgeNotAnEnd() {
        assertTrue(TicketStatus.RETURNED.canTransitionTo(TicketStatus.PENDING));
        assertTrue(TicketStatus.RETURNED.canTransitionTo(TicketStatus.CLOSED));
        // 信息不全的单子不该被"标记为已解决"了事
        assertFalse(TicketStatus.RETURNED.canTransitionTo(TicketStatus.RESOLVED));
        assertFalse(TicketStatus.RETURNED.canTransitionTo(TicketStatus.ASSIGNED));
        // 打回本身不算已决，不该写决定时间
        assertFalse(TicketStatus.RETURNED.isDecided());
    }

    @Test
    @DisplayName("处理中与已指派都可以打回：人工发现自己处理不了")
    void assignedAndProcessingCanReturn() {
        assertTrue(TicketStatus.ASSIGNED.canTransitionTo(TicketStatus.RETURNED));
        assertTrue(TicketStatus.PROCESSING.canTransitionTo(TicketStatus.RETURNED));
        assertTrue(TicketStatus.INFO_INCOMPLETE.canTransitionTo(TicketStatus.RETURNED));
    }

    @Test
    @DisplayName("isDecided 只对已解决与已关闭成立")
    void decidedFlag() {
        assertTrue(TicketStatus.RESOLVED.isDecided());
        assertTrue(TicketStatus.CLOSED.isDecided());
        assertFalse(TicketStatus.PENDING.isDecided());
        assertFalse(TicketStatus.PROCESSING.isDecided());
        assertFalse(TicketStatus.INFO_INCOMPLETE.isDecided());
    }

    @Test
    @DisplayName("null 目标状态不会崩")
    void nullTarget() {
        assertFalse(TicketStatus.PENDING.canTransitionTo(null));
    }
}
