package com.example.ticket.chat;

import com.example.ticket.chat.dto.TicketStatusView;
import com.example.ticket.common.BizException;
import com.example.ticket.ticket.Ticket;
import com.example.ticket.ticket.TicketMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 状态回执的单元测试。
 *
 * 守三件事：
 *   1 还没出单时不回执
 *   2 同一状态只回执一次（否则轮询会刷屏）
 *   3 文案里带真实的处理结果，不是笼统的「已处理」
 */
class ChatStatusServiceTest {

    private InMemorySessionStore store;
    private TicketMapper ticketMapper;
    private ReturnFlow returnFlow;
    private ChatStatusService service;

    private Long sessionId;
    private Ticket ticket;

    @BeforeEach
    void setUp() {
        store = new InMemorySessionStore();
        ticketMapper = mock(TicketMapper.class);
        returnFlow = mock(ReturnFlow.class);
        service = new ChatStatusService(store, ticketMapper, returnFlow);

        sessionId = store.create(1L, 3).getId();

        ticket = new Ticket();
        ticket.setId(25L);
        ticket.setStatus("PENDING");
        when(ticketMapper.selectById(25L)).thenReturn(ticket);
    }

    private void bindTicket() {
        SessionView v = store.load(sessionId);
        v.setTicketId(25L);
        store.save(v);
    }

    @Test
    @DisplayName("还没出单：返回 null，不产生任何回执")
    void noTicketYet() {
        assertNull(service.check(sessionId));
        assertEquals(0, store.messagesOf(sessionId).size());
    }

    @Test
    @DisplayName("初始状态（待处理/信息不全）不通知——顾客出单时已经被告知过了")
    void initialStatesAreSilent() {
        bindTicket();

        assertFalse(service.check(sessionId).isChanged());
        ticket.setStatus("INFO_INCOMPLETE");
        assertFalse(service.check(sessionId).isChanged());
        assertEquals(0, store.messagesOf(sessionId).size());
    }

    @Test
    @DisplayName("人工受理：产生一条回执，并记录已通知状态")
    void assignedProducesNotice() {
        bindTicket();
        ticket.setStatus("ASSIGNED");

        TicketStatusView view = service.check(sessionId);

        assertTrue(view.isChanged());
        assertEquals("ASSIGNED", view.getStatus());
        assertTrue(view.getNotice().contains("#25"));
        assertTrue(view.getNotice().contains("受理"), view.getNotice());
        assertEquals(1, store.messagesOf(sessionId).size());
        assertEquals("assistant", store.messagesOf(sessionId).get(0).getRole());
        assertEquals("ASSIGNED", store.load(sessionId).getLastNotifiedStatus());
    }

    @Test
    @DisplayName("同一状态重复轮询：只回执一次")
    void repeatedPollingIsIdempotent() {
        bindTicket();
        ticket.setStatus("ASSIGNED");

        assertTrue(service.check(sessionId).isChanged());
        assertFalse(service.check(sessionId).isChanged());
        assertFalse(service.check(sessionId).isChanged());
        assertEquals(1, store.messagesOf(sessionId).size(), "重复轮询不能刷屏");
    }

    @Test
    @DisplayName("状态继续往前走：再回执一次，并把处理结果带上")
    void nextStatusNotifiesAgainWithDecision() {
        bindTicket();
        ticket.setStatus("ASSIGNED");
        service.check(sessionId);

        ticket.setStatus("RESOLVED");
        ticket.setDecision("批准换货");
        TicketStatusView view = service.check(sessionId);

        assertTrue(view.isChanged());
        assertTrue(view.getNotice().contains("处理完成"), view.getNotice());
        assertTrue(view.getNotice().contains("批准换货"), view.getNotice());
        assertEquals(2, store.messagesOf(sessionId).size());
    }

    @Test
    @DisplayName("已解决但没有填处理结果：文案不硬拼空内容")
    void resolvedWithoutDecision() {
        bindTicket();
        ticket.setStatus("RESOLVED");

        TicketStatusView view = service.check(sessionId);
        assertTrue(view.isChanged());
        assertTrue(view.getNotice().endsWith("已处理完成。"), view.getNotice());
    }

    @Test
    @DisplayName("打回：回执告诉顾客要去对话里补充")
    void returnedNotifies() {
        bindTicket();
        ticket.setStatus("RETURNED");

        TicketStatusView view = service.check(sessionId);

        assertTrue(view.isChanged());
        assertTrue(view.getNotice().contains("补充"), view.getNotice());
    }

    @Test
    @DisplayName("补全后回到 PENDING：也通知一次「已重新提交」")
    void resubmittedNotifies() {
        bindTicket();
        ticket.setStatus("RETURNED");
        service.check(sessionId);                       // 先回执「要补充」

        ticket.setStatus("PENDING");                    // 顾客补完，重新排队
        TicketStatusView view = service.check(sessionId);

        assertTrue(view.isChanged());
        assertTrue(view.getNotice().contains("重新提交"), view.getNotice());
    }

    @Test
    @DisplayName("工单被删或数据坏了：报 404 而不是静默")
    void missingTicket() {
        bindTicket();
        when(ticketMapper.selectById(25L)).thenReturn(null);

        BizException e = assertThrows(BizException.class, () -> service.check(sessionId));
        assertEquals(404, e.getCode());
    }
}
