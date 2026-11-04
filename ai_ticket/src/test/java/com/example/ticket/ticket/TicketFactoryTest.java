package com.example.ticket.ticket;

import com.example.ticket.chat.TicketDraft;
import com.example.ticket.quality.QualityLevel;
import com.example.ticket.quality.QualityReport;
import com.example.ticket.template.FieldDef;
import com.example.ticket.template.FieldType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 落库工厂的单元测试。
 *
 * 它守的是「工单存下来的东西能不能自解释」：
 * schema 快照、质量明细、状态、以及回问率埋点，
 * 少一样，人工事后都没法解释「为什么这张工单是红的」。
 */
class TicketFactoryTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final TicketMapper ticketMapper = mock(TicketMapper.class);

    private final TicketFactory factory = new TicketFactory(ticketMapper, mapper);

    private TicketDraft draft(String status) {
        QualityReport quality = new QualityReport();
        quality.setLevel(QualityLevel.RED);
        quality.getDecision().getMissingRequired().add("order_id");
        quality.getMessages().add("缺 1 项必填：订单号");

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("order_id", null);
        values.put("reason", "尺码不合适");
        Map<String, String> basis = new LinkedHashMap<>();
        basis.put("reason", "尺码太大了");

        TicketDraft draft = new TicketDraft();
        draft.setSessionId(9L);
        draft.setTemplateId(1L);
        draft.setTemplateVersion(3);
        draft.setFields(List.of(
                new FieldDef("order_id", "订单号", FieldType.TEXT, true, null, "^\\d{8,20}$"),
                new FieldDef("reason", "原因", FieldType.ENUM, true,
                        List.of("尺码不合适"), null)));
        draft.setDecisionOptions(List.of("批准换货", "拒绝"));
        draft.setValues(values);
        draft.setBasis(basis);
        draft.setSummary("用户希望换码");
        draft.setSuggestion(null);
        draft.setQuality(quality);
        draft.setStatus(status);
        return draft;
    }

    private Ticket capture(TicketDraft draft) {
        when(ticketMapper.insert(any(Ticket.class))).thenAnswer(inv -> {
            Ticket t = inv.getArgument(0);
            t.setId(7L);
            return 1;
        });
        Long id = factory.publish(draft);
        assertEquals(7L, id);

        ArgumentCaptor<Ticket> captor = ArgumentCaptor.forClass(Ticket.class);
        verify(ticketMapper).insert(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("落库字段与草稿一致，并把 id 交回去")
    void persistsDraft() throws Exception {
        Ticket saved = capture(draft("PENDING"));

        assertEquals(1L, saved.getTemplateId());
        assertEquals(3, saved.getTemplateVersion());
        assertEquals("RED", saved.getQualityLevel());
        assertEquals("PENDING", saved.getStatus());
        assertEquals(9L, saved.getSessionId());
        assertEquals("用户希望换码", saved.getSummary());
        assertNotNull(saved.getCreatedAt());

        JsonNode values = mapper.readTree(saved.getValuesJson());
        assertTrue(values.has("order_id"));
        assertEquals("尺码不合适", values.get("reason").asText());

        JsonNode basis = mapper.readTree(saved.getBasis());
        assertEquals("尺码太大了", basis.get("reason").asText());
    }

    @Test
    @DisplayName("schema 快照含版本、决策项与字段，不含 role/knowledge")
    void schemaSnapshotContent() throws Exception {
        Ticket saved = capture(draft("PENDING"));
        JsonNode snapshot = mapper.readTree(saved.getSchemaSnapshotJson());

        assertEquals(3, snapshot.get("version").asInt());
        assertEquals(2, snapshot.get("decisionOptions").size());
        assertEquals(2, snapshot.get("fields").size());
        assertEquals("order_id", snapshot.get("fields").get(0).get("key").asText());
        // role / knowledge 对「工单长什么样」没有影响，不进快照
        assertFalse(snapshot.has("role"));
        assertFalse(snapshot.has("knowledge"));
    }

    @Test
    @DisplayName("质量明细存的是三维度全量，不只是结论")
    void qualityDetailIsStored() throws Exception {
        Ticket saved = capture(draft("PENDING"));
        JsonNode detail = mapper.readTree(saved.getQualityDetailJson());

        assertEquals("RED", detail.get("level").asText());
        assertTrue(detail.has("decision"));
        assertTrue(detail.has("faithfulness"));
        assertTrue(detail.has("consistency"));
        assertTrue(detail.has("metrics"));
        assertEquals(1, detail.get("messages").size());
    }

    @Test
    @DisplayName("信息不全的出单会置 needsClarify——这是回问率的埋点")
    void infoIncompleteSetsNeedsClarify() {
        Ticket saved = capture(draft("INFO_INCOMPLETE"));
        assertEquals("INFO_INCOMPLETE", saved.getStatus());
        assertTrue(saved.getNeedsClarify());
    }

    @Test
    @DisplayName("正常出单时 needsClarify 为 false")
    void normalTicketDoesNotNeedClarify() {
        Ticket saved = capture(draft("PENDING"));
        assertFalse(saved.getNeedsClarify());
    }

    @Test
    @DisplayName("状态为空时默认待处理")
    void defaultStatus() {
        Ticket saved = capture(draft(null));
        assertEquals("PENDING", saved.getStatus());
    }

    @Test
    @DisplayName("草稿缺核验结果时直接拒绝，不产生半成品工单")
    void rejectsIncompleteDraft() {
        TicketDraft broken = draft("PENDING");
        broken.setQuality(null);

        com.example.ticket.common.BizException e =
                org.junit.jupiter.api.Assertions.assertThrows(
                        com.example.ticket.common.BizException.class,
                        () -> factory.publish(broken));
        assertEquals(500, e.getCode());
    }
}
