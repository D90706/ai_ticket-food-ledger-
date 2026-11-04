package com.example.ledger.record;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 台账记录状态机。
 *
 *   PENDING  待入账   录进来了，还没审核
 *   POSTED   已入账   管理员确认，这笔采购正式进台账
 *   RETURNED 退回补录 资料不全，退回给采购员补
 *   VOID     已作废   录错了，作废但留痕（台账不允许真删）
 *
 * 为什么不提供 DELETE：
 * 台账是给监管看的东西，删行等于抹掉痕迹。
 * 错就作废，作废要比删除诚实。
 */
public enum LedgerStatus {

    PENDING,
    POSTED,
    RETURNED,
    VOID;

    private static final Map<LedgerStatus, Set<LedgerStatus>> TRANSITIONS =
            new EnumMap<>(LedgerStatus.class);

    static {
        // 待入账：可以入账、可以退回补录、可以直接作废
        TRANSITIONS.put(PENDING, EnumSet.of(POSTED, RETURNED, VOID));
        // 退回补录：补完回到待入账；补不出来就作废
        TRANSITIONS.put(RETURNED, EnumSet.of(PENDING, VOID));
        // 已入账：事后发现错了只能作废，不能悄悄改回去
        TRANSITIONS.put(POSTED, EnumSet.of(VOID));
        // 作废是终态
        TRANSITIONS.put(VOID, EnumSet.noneOf(LedgerStatus.class));
    }

    public boolean canTransitionTo(LedgerStatus next) {
        return next != null && TRANSITIONS.getOrDefault(this, Set.of()).contains(next);
    }

    public boolean isEditable() {
        return this == PENDING || this == RETURNED;
    }
}