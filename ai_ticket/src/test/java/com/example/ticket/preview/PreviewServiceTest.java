package com.example.ticket.preview;

import com.example.ticket.extraction.ExtractionResult;
import com.example.ticket.extraction.Extractor;
import com.example.ticket.extraction.JsonSchemaValidator;
import com.example.ticket.preview.dto.PreviewResponse;
import com.example.ticket.quality.ConsistencyChecker;
import com.example.ticket.quality.FaithfulnessChecker;
import com.example.ticket.quality.MissingAnalyzer;
import com.example.ticket.quality.QualityEvaluator;
import com.example.ticket.template.FieldDef;
import com.example.ticket.template.TemplateSchema;
import com.example.ticket.template.TemplateService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 试抽服务的单元测试。
 *
 * 关键的一条：试抽的结果必须与 mock/preview-response.json 完全一致。
 * 否则「模板作者看到的」和「系统真实会做的」就是两回事，
 * 试抽这个接口也就失去了意义。
 */
class PreviewServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private final TemplateService templateService = mock(TemplateService.class);
    private final Extractor extractor = mock(Extractor.class);

    private final PreviewService service = new PreviewService(
            templateService,
            extractor,
            new QualityEvaluator(
                    new MissingAnalyzer(),
                    new FaithfulnessChecker(),
                    new ConsistencyChecker(),
                    new JsonSchemaValidator(),
                    mapper));

    private static final String DIALOGUE =
            "您好，我上周买的卫衣订单号 2026100812345678，收到的M码太大了，"
                    + "我平时穿S的，想换成S码。衣服吊牌还没剪，也没穿过。";

    private TemplateSchema schema() {
        return new TemplateSchema(
                "电商售后工单", 3, "你是售后助手", "知识",
                List.of("批准换货", "批准退货", "拒绝", "要求补证", "转售后专岗"),
                List.of(
                        new FieldDef("order_id", "订单号", com.example.ticket.template.FieldType.TEXT,
                                true, null, "^\\d{8,20}$"),
                        new FieldDef("reason", "原因", com.example.ticket.template.FieldType.ENUM,
                                true, List.of("尺码不合适", "质量问题", "发错货", "其他"), null),
                        new FieldDef("worn", "是否已穿着或洗涤",
                                com.example.ticket.template.FieldType.BOOLEAN, false, null, null),
                        new FieldDef("size_wanted", "希望换成的尺码",
                                com.example.ticket.template.FieldType.TEXT, false, null,
                                "^(XS|S|M|L|XL|XXL)$"),
                        new FieldDef("evidence", "商品问题照片",
                                com.example.ticket.template.FieldType.IMAGE, false, null, null)));
    }

    private ExtractionResult extractionOf(Map<String, Object> values,
                                          Map<String, String> basis,
                                          String suggestion, String summary) {
        ExtractionResult r = new ExtractionResult();
        Map<String, Object> v = new LinkedHashMap<>();
        Map<String, String> b = new LinkedHashMap<>();
        for (FieldDef f : schema().getFields()) {
            v.put(f.getKey(), values.get(f.getKey()));
            b.put(f.getKey(), basis == null ? null : basis.get(f.getKey()));
        }
        r.setValues(v);
        r.setBasis(b);
        r.setSuggestion(suggestion);
        r.setSummary(summary);
        r.setAttempts(1);
        return r;
    }

    private ExtractionResult completeExtraction() {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("order_id", "2026100812345678");
        v.put("reason", "尺码不合适");
        v.put("worn", false);
        v.put("size_wanted", "S");
        Map<String, String> b = new LinkedHashMap<>();
        b.put("order_id", "订单号 2026100812345678");
        b.put("reason", "收到的M码太大了");
        b.put("worn", "也没穿过");
        b.put("size_wanted", "想换成S码");
        return extractionOf(v, b, "批准换货",
                "顾客收到的 M 码卫衣偏大，未试穿、吊牌未剪，希望换成 S 码。");
    }

    @Test
    @DisplayName("试抽结果与 mock/preview-response.json 完全一致")
    void matchesMock() throws Exception {
        when(templateService.getSchema(1L)).thenReturn(schema());
        when(extractor.extract(any(), eq(DIALOGUE))).thenReturn(completeExtraction());

        JsonNode data = mapper.readTree(Files.readString(
                Path.of("mock/preview-response.json"), StandardCharsets.UTF_8)).path("data");

        PreviewResponse actual = service.preview(1L, DIALOGUE);

        assertEquals(data.path("values").get("order_id").asText(),
                String.valueOf(actual.getValues().get("order_id")));
        assertEquals(data.path("basis").get("reason").asText(),
                actual.getBasis().get("reason"));
        assertEquals(data.path("suggestion").asText(), actual.getSuggestion());
        assertEquals(data.path("summary").asText(), actual.getSummary());
        assertEquals(data.path("quality").path("level").asText(),
                actual.getQuality().getLevel().name());
        assertEquals(data.path("quality").path("metrics").path("fillRate").asDouble(),
                actual.getQuality().getMetrics().getFillRate(), 0.0001);
        assertEquals(mapper.convertValue(data.path("quality").path("messages"),
                        new TypeReference<List<String>>() {
                        }),
                actual.getQuality().getMessages());
    }

    @Test
    @DisplayName("缺必填时建议被置空——试抽与真实出单必须表现一致")
    void suggestionDroppedWhenRequiredMissing() {
        when(templateService.getSchema(1L)).thenReturn(schema());
        when(extractor.extract(any(), any())).thenReturn(
                extractionOf(Map.of(), null, "批准换货", "用户要售后"));

        PreviewResponse actual = service.preview(1L, "我买的裤子有问题");

        assertNull(actual.getSuggestion());
        assertEquals("RED", actual.getQuality().getLevel().name());
        assertTrue(actual.getQuality().getMessages().get(0).startsWith("缺 2 项必填"));
    }

    @Test
    @DisplayName("空对话被拒绝")
    void rejectsBlankDialogue() {
        com.example.ticket.common.BizException e =
                org.junit.jupiter.api.Assertions.assertThrows(
                        com.example.ticket.common.BizException.class,
                        () -> service.preview(1L, "   "));
        assertNotNull(e.getMessage());
    }

    @Test
    @DisplayName("values/basis 覆盖全部字段，方便前端按 schema 渲染")
    void valuesCoverAllFields() {
        when(templateService.getSchema(1L)).thenReturn(schema());
        when(extractor.extract(any(), any())).thenReturn(completeExtraction());

        PreviewResponse actual = service.preview(1L, DIALOGUE);
        assertEquals(5, actual.getValues().size());
        assertEquals(5, actual.getBasis().size());
        assertEquals(new ArrayList<>(schema().keys()).size(), actual.getValues().size());
    }
}
