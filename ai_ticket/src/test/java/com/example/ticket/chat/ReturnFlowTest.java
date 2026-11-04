package com.example.ticket.chat;

import com.example.ticket.quality.MissingAnalyzer;
import com.example.ticket.template.FieldDef;
import com.example.ticket.template.FieldType;
import com.example.ticket.template.TemplateSchema;
import com.example.ticket.template.TemplateService;
import com.example.ticket.ticket.Ticket;
import com.example.ticket.ticket.TicketMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 打回重开的单元测试。
 *
 * 守四件事：
 *   1 只有「工单被打回 + 会话还是 TICKETED」才重开
 *   2 重开只发生一次（第二次调用不再写追问）
 *   3 重开时把追问预算重新给满，否则顾客刚要回答就被推到确认卡上
 *   4 人工留了话就用人工的话，没留才按缺失字段自动生成
 */
class ReturnFlowTest {

    private InMemorySessionStore store;
    private TicketMapper ticketMapper;
    private TemplateService templateService;
    private ReturnFlow flow;

    private Long sessionId;
    private Ticket ticket;


    private TemplateSchema schema() {
        return new TemplateSchema(
                "电商售后工单", 3, "你是售后助手", "知识",
                List.of("批准换货", "拒绝"),
                List.of(
                        new FieldDef("order_id", "订单号", FieldType.TEXT, true, null, null),
                        new FieldDef("reason", "原因", FieldType.ENUM, true,
                                List.of("尺码不合适", "质量问题"), null)));
    }

    @BeforeEach
    void setUp() {
        store = new InMemorySessionStore();
        ticketMapper = mock(TicketMapper.class);
        templateService = mock(TemplateService.class);
        when(templateService.getSchema(1L)).thenReturn(schema());

        flow = new ReturnFlow(store, ticketMapper, templateService, new MissingAnalyzer());

        sessionId = store.create(1L, 3).getId();
        SessionView session = store.load(sessionId);
        session.setTicketId(25L);
        session.setState(SessionState.TICKETED);
        session.setFollowupCount(2);
        store.save(session);

        ticket = new Ticket();
        ticket.setId(25L);
        ticket.setStatus("RETURNED");
        ticket.setReturnCount(1);
        when(ticketMapper.selectById(25L)).thenReturn(ticket);
    }

    @Test
    @DisplayName("工单被打回 + 会话已出单 → 重开，追问预算清零，写入追问")
    void reopensSession() {
        boolean reopened = flow.reopenIfReturned(store.load(sessionId));

        assertTrue(reopened);
        SessionView after = store.load(sessionId);
        assertEquals(SessionState.CHATTING, after.getState());
        assertEquals(0, after.getFollowupCount(), "追问预算要重新给满");
        assertEquals(1, store.messagesOf(sessionId).size());
        assertTrue(store.messagesOf(sessionId).get(0).getContent().contains("订单号"),
                "缺的字段要出现在追问里");
    }

    @Test
    @DisplayName("重开只发生一次：第二次调用不再写追问")
    void reopensOnlyOnce() {
        assertTrue(flow.reopenIfReturned(store.load(sessionId)));
        assertFalse(flow.reopenIfReturned(store.load(sessionId)));
        assertEquals(1, store.messagesOf(sessionId).size());
    }

    @Test
    @DisplayName("工单没被打回：不动会话")
    void ignoresNormalTicket() {
        ticket.setStatus("PROCESSING");
        assertFalse(flow.reopenIfReturned(store.load(sessionId)));
        assertEquals(SessionState.TICKETED, store.load(sessionId).getState());
    }

    @Test
    @DisplayName("还没出单的会话：不动")
    void ignoresSessionWithoutTicket() {
        SessionView fresh = store.create(1L, 3);
        assertFalse(flow.reopenIfReturned(fresh));
    }

    @Test
    @DisplayName("人工留了话就用人工的话，不用自动生成")
    void prefersHumanNote() {
        ticket.setReturnNote("麻烦您提供下单时用的手机号后四位");
        flow.reopenIfReturned(store.load(sessionId));

        String ask = store.messagesOf(sessionId).get(0).getContent();
        assertEquals("麻烦您提供下单时用的手机号后四位", ask);
    }

    @Test
    @DisplayName("缺的必填项按中文字段名列出")
    void listsMissingLabels() {
        SessionView session = store.load(sessionId);
        session.getValues().put("order_id", "2026100812345678");
        assertEquals(List.of("原因"), flow.missingLabels(session));
    }

    @Test
    @DisplayName("什么都不缺时给一句通用追问，不要空白")
    void noMissingFallsBack() {
        SessionView session = store.load(sessionId);
        session.getValues().put("order_id", "2026100812345678");
        session.getValues().put("reason", "尺码不合适");
        assertTrue(flow.missingLabels(session).isEmpty());

        flow.reopenIfReturned(store.load(sessionId));
        String ask = store.messagesOf(sessionId).get(0).getContent();
        assertTrue(ask.contains("补充"), ask);
    }
}
