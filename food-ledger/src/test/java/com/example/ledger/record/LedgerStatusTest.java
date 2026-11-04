package com.example.ledger.record;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 台账状态机测试。
 *
 * 两条不能破的规矩：
 *   已入账的记录不能改回待入账（事后发现错了只能作废）
 *   作废是终态（谁也别想复活一条已作废的记录）
 */
class LedgerStatusTest {

    @Test
    @DisplayName("待入账：可入账、可退回、可作废")
    void pending() {
        assertTrue(LedgerStatus.PENDING.canTransitionTo(LedgerStatus.POSTED));
        assertTrue(LedgerStatus.PENDING.canTransitionTo(LedgerStatus.RETURNED));
        assertTrue(LedgerStatus.PENDING.canTransitionTo(LedgerStatus.VOID));
        assertTrue(LedgerStatus.PENDING.isEditable());
    }

    @Test
    @DisplayName("已入账：只能作废，不能退回待入账")
    void posted() {
        assertTrue(LedgerStatus.POSTED.canTransitionTo(LedgerStatus.VOID));
        assertFalse(LedgerStatus.POSTED.canTransitionTo(LedgerStatus.PENDING),
                "已入账不能悄悄改回待入账");
        assertFalse(LedgerStatus.POSTED.isEditable(), "已入账的记录内容不可再改");
    }

    @Test
    @DisplayName("退回补录：补完回待入账，或直接作废")
    void returned() {
        assertTrue(LedgerStatus.RETURNED.canTransitionTo(LedgerStatus.PENDING));
        assertTrue(LedgerStatus.RETURNED.canTransitionTo(LedgerStatus.VOID));
        assertFalse(LedgerStatus.RETURNED.canTransitionTo(LedgerStatus.POSTED),
                "退回中的记录要先补录再入账，不能直接入账");
        assertTrue(LedgerStatus.RETURNED.isEditable());
    }

    @Test
    @DisplayName("作废是终态")
    void voidIsTerminal() {
        for (LedgerStatus next : LedgerStatus.values()) {
            assertFalse(LedgerStatus.VOID.canTransitionTo(next));
        }
    }
}