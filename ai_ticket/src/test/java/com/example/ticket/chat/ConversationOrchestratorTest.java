package com.example.ticket.chat;

import com.example.ticket.common.BizException;
import com.example.ticket.extraction.ExtractionResult;
import com.example.ticket.extraction.Extractor;
import com.example.ticket.extraction.JsonSchemaValidator;
import com.example.ticket.quality.MissingAnalyzer;
import com.example.ticket.quality.QualityEvaluator;
import com.example.ticket.quality.QualityLevel;
import com.example.ticket.template.FieldDef;
import com.example.ticket.template.FieldType;
import com.example.ticket.template.TemplateSchema;
import com.example.ticket.template.TemplateService;
import com.example.ticket.ticket.TicketFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 编排逻辑的单元测试：全系统唯一的业务分支。
 *
 * 用内存会话 + 脚本化抽取器，不碰数据库、不碰网络。
 * 守的是四条硬规则：
 *   1 缺必填就追问，问够上限后按配置走「待确认」或直接出单
 *   2 缺必填时丢弃模型建议
 *   3 出单后会话关闭
 *   4 确认只对信息不全的工单生效，且「确认过」这件事记在工单上
 */
class ConversationOrchestratorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private InMemorySessionStore store;
    private Extractor extractor;
    private TemplateService templateService;
    private TicketFactory ticketFactory;
    private ReturnFlow returnFlow;
    private ConversationOrchestrator orchestrator;

    private Long sessionId;

    private TemplateSchema schema() {
        return new TemplateSchema(
                "电商售后工单", 3, "你是售后助手", "知识",
                List.of("批准换货", "拒绝"),
                List.of(
                        new FieldDef("order_id", "订单号", FieldType.TEXT, true, null, "^\\d{8,20}$"),
                        new FieldDef("reason", "原因", FieldType.ENUM, true,
                                List.of("尺码不合适", "质量问题"), null),
                        new FieldDef("evidence", "商品问题照片", FieldType.IMAGE, false, null, null)));
    }

    @BeforeEach
    void setUp() {
        store = new InMemorySessionStore();
        extractor = mock(Extractor.class);
        templateService = mock(TemplateService.class);
        ticketFactory = mock(TicketFactory.class);
        returnFlow = mock(ReturnFlow.class);
        when(templateService.getSchema(1L)).thenReturn(schema());
        when(ticketFactory.publish(any())).thenReturn(42L);

        orchestrator = new ConversationOrchestrator(
                store,
                templateService,
                extractor,
                new MissingAnalyzer(),
                new QualityEvaluator(new MissingAnalyzer(),
                        new com.example.ticket.quality.FaithfulnessChecker(),
                        new com.example.ticket.quality.ConsistencyChecker(),
                        new JsonSchemaValidator(),
                        mapper),
                new FollowUpBuilder(),
                ticketFactory,
                returnFlow);

        sessionId = store.create(1L, 3).getId();
    }

    // ---------------------------------------------------------- 夹具

    private ExtractionResult extraction(Map<String, Object> values,
                                        Map<String, String> basis,
                                        String suggestion, String summary) {
        ExtractionResult r = new ExtractionResult();
        Map<String, Object> v = new LinkedHashMap<>();
        for (FieldDef f : schema().getFields()) {
            v.put(f.getKey(), values.get(f.getKey()));
        }
        r.setValues(v);
        Map<String, String> b = new LinkedHashMap<>();
        for (FieldDef f : schema().getFields()) {
            b.put(f.getKey(), basis == null ? null : basis.get(f.getKey()));
        }
        r.setBasis(b);
        r.setSuggestion(suggestion);
        r.setSummary(summary);
        r.setAttempts(1);
        return r;
    }

    private ExtractionResult onlyOrderId() {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("order_id", "2026100812345678");
        Map<String, String> b = new LinkedHashMap<>();
        b.put("order_id", "订单号 2026100812345678");
        return extraction(v, b, "批准换货", "用户要售后");
    }

    private ExtractionResult complete() {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("order_id", "2026100812345678");
        v.put("reason", "尺码不合适");
        Map<String, String> b = new LinkedHashMap<>();
        b.put("order_id", "订单号 2026100812345678");
        b.put("reason", "尺码太大了");
        return extraction(v, b, "批准换货", "用户希望换码");
    }

    /** 追问到上限：三次缺必填，第三次应当转「待确认」 */
    private OrchestratorResult driveToConfirm() {
        when(extractor.extract(any(), any())).thenReturn(onlyOrderId());
        orchestrator.handle(sessionId, "第一次");
        orchestrator.handle(sessionId, "第二次");
        return orchestrator.handle(sessionId, "第三次");
    }

    // ---------------------------------------------------------- 追问

    @Test
    @DisplayName("缺必填 → 追问，问句里用中文字段名，追问次数 +1")
    void missingRequiredAsks() {
        when(extractor.extract(any(), any())).thenReturn(onlyOrderId());

        OrchestratorResult result = orchestrator.handle(sessionId, "我买的衣服有问题");

        assertEquals(OrchestratorResult.ReplyType.ASK, result.replyType());
        assertEquals(1, result.followupCount());
        assertTrue(result.question().contains("原因"), result.question());
        assertTrue(result.question().startsWith("为了帮您处理"));
        assertNull(result.draft());

        List<ChatMessage> history = store.messagesOf(sessionId);
        assertEquals("user", history.get(0).getRole());
        assertEquals("assistant", history.get(1).getRole());
        assertEquals(result.question(), history.get(1).getContent());
    }

    @Test
    @DisplayName("追问上限 → 转待确认：先不落库，给一句话摘要 + 还缺什么")
    void followupLimitRequestsConfirm() {
        OrchestratorResult third = driveToConfirm();

        assertEquals(OrchestratorResult.ReplyType.CONFIRM, third.replyType());
        assertEquals("RED", third.level());
        assertNotNull(third.confirmSummary());
        assertEquals(List.of("原因"), third.missingLabels());
        // 关键：顾客确认之前不能落库
        verify(ticketFactory, never()).publish(any());
        assertEquals(SessionState.AWAITING_CONFIRM, store.load(sessionId).getState());
        assertEquals(third.confirmSummary(), store.load(sessionId).getDraftSummary());
    }

    @Test
    @DisplayName("摘要缺失时用已抽到的字段兜底，不能给顾客一张空白确认卡")
    void confirmSummaryFallsBackToFields() {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("order_id", "2026100812345678");
        Map<String, String> b = new LinkedHashMap<>();
        b.put("order_id", "订单号 2026100812345678");
        // 模型这次没给 summary
        when(extractor.extract(any(), any())).thenReturn(extraction(v, b, null, null));

        orchestrator.handle(sessionId, "第一次");
        orchestrator.handle(sessionId, "第二次");
        OrchestratorResult third = orchestrator.handle(sessionId, "第三次");

        assertEquals(OrchestratorResult.ReplyType.CONFIRM, third.replyType());
        assertTrue(third.confirmSummary().contains("订单号"), third.confirmSummary());
        assertTrue(third.confirmSummary().contains("2026100812345678"));
    }

    // ---------------------------------------------------------- 确认

    @Test
    @DisplayName("顾客确认 → 出单，标记「已确认」，摘要采用顾客改过的那句")
    void confirmPublishesTicket() {
        driveToConfirm();

        OrchestratorResult result = orchestrator.confirm(sessionId, "顾客改过的摘要：想换 XL");

        assertEquals(OrchestratorResult.ReplyType.TICKET, result.replyType());
        assertEquals(42L, result.ticketId());
        assertEquals("RED", result.level());
        assertTrue(result.messages().contains(ConversationOrchestrator.INFO_INCOMPLETE_NOTICE));

        ArgumentCaptor<TicketDraft> captor = ArgumentCaptor.forClass(TicketDraft.class);
        verify(ticketFactory, times(1)).publish(captor.capture());
        TicketDraft published = captor.getValue();

        assertTrue(published.isCustomerConfirmed());
        assertEquals("顾客改过的摘要：想换 XL", published.getSummary());
        assertEquals("INFO_INCOMPLETE", published.getStatus());
        assertNull(published.getSuggestion(), "缺必填时不能带模型建议");

        assertEquals(SessionState.TICKETED, store.load(sessionId).getState());
        assertNull(store.load(sessionId).getDraftSummary(), "出单后应清掉临时摘要");
    }

    @Test
    @DisplayName("顾客不改摘要直接确认，用的也是系统整理的那版")
    void confirmWithoutEditing() {
        OrchestratorResult third = driveToConfirm();
        String original = third.confirmSummary();

        orchestrator.confirm(sessionId, null);

        ArgumentCaptor<TicketDraft> captor = ArgumentCaptor.forClass(TicketDraft.class);
        verify(ticketFactory).publish(captor.capture());
        assertEquals(original, captor.getValue().getSummary());
        assertTrue(captor.getValue().isCustomerConfirmed());
    }

    @Test
    @DisplayName("没有待确认状态时不能确认")
    void confirmRequiresPendingState() {
        BizException e = assertThrows(BizException.class,
                () -> orchestrator.confirm(sessionId, "x"));
        assertEquals(409, e.getCode());
    }

    @Test
    @DisplayName("确认后会话关闭，不能再发消息")
    void confirmClosesSession() {
        driveToConfirm();
        orchestrator.confirm(sessionId, null);

        BizException e = assertThrows(BizException.class,
                () -> orchestrator.handle(sessionId, "还有个事"));
        assertEquals(409, e.getCode());
    }

    @Test
    @DisplayName("待确认时又补充了一句：重新抽取，补齐了就直出，不必再确认")
    void supplementAfterConfirmRequest() {
        driveToConfirm();
        // 第四次抽取补齐了原因
        when(extractor.extract(any(), any())).thenReturn(complete());

        OrchestratorResult result = orchestrator.handle(sessionId, "原因是尺码太大");

        assertEquals(OrchestratorResult.ReplyType.TICKET, result.replyType());
        assertEquals("PENDING", result.draft().getStatus());
        assertFalse(result.draft().isCustomerConfirmed(),
                "补充后直出的工单没经过确认，不应标记为已确认");
    }

    // ---------------------------------------------------------- 打回补全

    @Test
    @DisplayName("打回补全：顾客补齐后原地更新原工单，不新建一张")
    void supplementUpdatesExistingTicket() {
        when(extractor.extract(any(), any())).thenReturn(complete());

        // 模拟：工单已被打回，会话已重开（有 ticketId，状态 CHATTING）
        SessionView session = store.load(sessionId);
        session.setTicketId(25L);
        store.save(session);

        OrchestratorResult result = orchestrator.handle(sessionId, "订单号 2026100812345678，尺码太大了");

        assertEquals(OrchestratorResult.ReplyType.TICKET, result.replyType());
        assertEquals(25L, result.ticketId(), "还是原来那张单");
        assertTrue(result.resubmitted(), "要标记为重新提交");
        assertFalse(result.draft().isCustomerConfirmed(), "补充后直出，没经过确认");

        verify(ticketFactory, times(1)).supplement(org.mockito.ArgumentMatchers.eq(25L), any());
        verify(ticketFactory, never()).publish(any());
    }

    @Test
    @DisplayName("打回后顾客来发消息：会话被重开，不会 409")
    void returnedSessionIsReopened() {
        when(extractor.extract(any(), any())).thenReturn(complete());
        when(returnFlow.reopenIfReturned(any())).thenAnswer(inv -> {
            SessionView s = inv.getArgument(0);
            s.setState(SessionState.CHATTING);
            s.setTicketId(25L);
            store.save(s);
            return true;
        });

        OrchestratorResult result = orchestrator.handle(sessionId, "补一句");

        assertEquals(OrchestratorResult.ReplyType.TICKET, result.replyType());
        verify(returnFlow).reopenIfReturned(any());
    }

    // ---------------------------------------------------------- 开关

    @Test
    @DisplayName("关掉确认开关：追问超限直接出单（旧行为）")
    void confirmDisabledIssuesDirectly() {
        ReflectionTestUtils.setField(orchestrator, "confirmBeforeTicket", false);
        when(extractor.extract(any(), any())).thenReturn(onlyOrderId());

        orchestrator.handle(sessionId, "第一次");
        orchestrator.handle(sessionId, "第二次");
        OrchestratorResult third = orchestrator.handle(sessionId, "第三次");

        assertEquals(OrchestratorResult.ReplyType.TICKET, third.replyType());
        assertEquals("RED", third.level());
        verify(ticketFactory).publish(any());
    }

    // ---------------------------------------------------------- 出单

    @Test
    @DisplayName("必填齐了 → 直接出单；选填缺失只让级别变黄")
    void completeIssuesTicket() {
        when(extractor.extract(any(), any())).thenReturn(complete());

        OrchestratorResult result = orchestrator.handle(sessionId,
                "订单号 2026100812345678，尺码太大了");

        assertEquals(OrchestratorResult.ReplyType.TICKET, result.replyType());
        assertEquals(QualityLevel.YELLOW.name(), result.level());
        assertEquals("PENDING", result.draft().getStatus());
        assertEquals("批准换货", result.draft().getSuggestion());
        assertTrue(result.messages().contains("缺 1 项选填：商品问题照片"));
        assertTrue(result.messages().stream().noneMatch(m -> m.contains("信息不全")));
        // 绿黄不打扰顾客：直出的工单不算「顾客确认过」
        assertFalse(result.draft().isCustomerConfirmed());
        verify(ticketFactory).publish(any());
    }

    @Test
    @DisplayName("缺必填时丢弃模型给的建议")
    void suggestionDroppedWhenRequiredMissing() {
        OrchestratorResult third = driveToConfirm();
        assertEquals(OrchestratorResult.ReplyType.CONFIRM, third.replyType());

        orchestrator.confirm(sessionId, null);

        ArgumentCaptor<TicketDraft> captor = ArgumentCaptor.forClass(TicketDraft.class);
        verify(ticketFactory).publish(captor.capture());
        assertNull(captor.getValue().getSuggestion(), "缺必填项时不能采用模型建议");
    }

    @Test
    @DisplayName("出单后会话关闭，不能再发消息")
    void ticketClosesSession() {
        when(extractor.extract(any(), any())).thenReturn(complete());
        orchestrator.handle(sessionId, "订单号 2026100812345678，尺码太大了");

        BizException e = assertThrows(BizException.class,
                () -> orchestrator.handle(sessionId, "还有个事"));
        assertEquals(409, e.getCode());
    }

    // ---------------------------------------------------------- 累积

    @Test
    @DisplayName("跨轮次累积：第一轮给的订单号不会被第二轮的缺失覆盖")
    void valuesAccumulateAcrossTurns() {
        Map<String, Object> v1 = new LinkedHashMap<>();
        v1.put("order_id", "2026100812345678");
        Map<String, String> b1 = new LinkedHashMap<>();
        b1.put("order_id", "订单号 2026100812345678");
        ExtractionResult first = extraction(v1, b1, null, "用户要售后");

        Map<String, Object> v2 = new LinkedHashMap<>();
        v2.put("reason", "尺码不合适");
        Map<String, String> b2 = new LinkedHashMap<>();
        b2.put("reason", "尺码太大了");
        ExtractionResult second = extraction(v2, b2, "批准换货", "用户希望换码");

        when(extractor.extract(any(), any())).thenReturn(first, second);

        assertEquals(OrchestratorResult.ReplyType.ASK,
                orchestrator.handle(sessionId, "我买的衣服有问题").replyType());

        OrchestratorResult result = orchestrator.handle(sessionId, "尺码太大了");
        assertEquals(OrchestratorResult.ReplyType.TICKET, result.replyType(),
                "第二轮补齐原因后应当直接出单，而不是继续追问订单号");
        assertEquals("2026100812345678", result.draft().getValues().get("order_id"));
        assertEquals("尺码不合适", result.draft().getValues().get("reason"));
    }

    // ---------------------------------------------------------- 异常

    @Test
    @DisplayName("空消息与不存在的会话都被拦下")
    void rejectsInvalidInput() {
        assertThrows(BizException.class, () -> orchestrator.handle(sessionId, "  "));

        BizException e = assertThrows(BizException.class,
                () -> orchestrator.handle(999L, "你好"));
        assertEquals(404, e.getCode());
    }

    @Test
    @DisplayName("草稿带上了 schema 快照，且快照是生成那一刻的")
    void draftCarriesSchemaSnapshot() {
        when(extractor.extract(any(), any())).thenReturn(complete());
        OrchestratorResult result = orchestrator.handle(sessionId,
                "订单号 2026100812345678，尺码太大了");

        TicketDraft draft = result.draft();
        assertEquals(1L, draft.getTemplateId());
        assertEquals(3, draft.getTemplateVersion());
        assertEquals(3, draft.getFields().size());
        assertEquals(2, draft.getDecisionOptions().size());
        assertNotNull(draft.getQuality());
        assertEquals(QualityLevel.YELLOW, draft.getQuality().getLevel());
    }

    @Test
    @DisplayName("抽取降级（全员缺失）时不会崩，会走追问")
    void degradedExtractionStillAsks() {
        ExtractionResult degraded = ExtractionResult.degradedOf(schema(), "坏输出", 3, List.of());
        when(extractor.extract(any(), any())).thenReturn(degraded);

        OrchestratorResult result = orchestrator.handle(sessionId, "我买的衣服有问题");
        assertEquals(OrchestratorResult.ReplyType.ASK, result.replyType());
    }
}
