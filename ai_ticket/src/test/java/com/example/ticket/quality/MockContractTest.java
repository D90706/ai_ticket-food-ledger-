package com.example.ticket.quality;

import com.example.ticket.extraction.JsonSchemaValidator;
import com.example.ticket.template.FieldDef;
import com.example.ticket.template.TemplateSchema;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 契约测试：用 mock/ 目录里的文件反推，验证核验层的输出与契约一致。
 *
 * 这个测试的意义在于：mock 是前后端约定的产物，
 * 如果核验层算出来的与 mock 不同，那 mock 就是假的契约。
 *
 * 它同时会把握出来的报告写进 target/quality/，
 * 方便人工核对「分级不是纸上设计」。
 */
class MockContractTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private final QualityEvaluator evaluator = new QualityEvaluator(
            new MissingAnalyzer(),
            new FaithfulnessChecker(),
            new ConsistencyChecker(),
            new JsonSchemaValidator(),
            mapper);

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 20, 9, 41, 12);

    private TemplateSchema schemaOf(JsonNode tpl) {
        List<String> options = new ArrayList<>();
        tpl.path("decisionOptions").forEach(n -> options.add(n.asText()));
        List<FieldDef> fields = mapper.convertValue(tpl.path("fields"),
                new TypeReference<List<FieldDef>>() {
                });
        return new TemplateSchema(
                tpl.path("name").asText(),
                tpl.path("version").asInt(),
                tpl.path("role").asText(),
                tpl.path("knowledge").asText(),
                options,
                fields);
    }

    private Map<String, Object> toValues(JsonNode node) {
        return mapper.convertValue(node, new TypeReference<LinkedHashMap<String, Object>>() {
        });
    }

    private Map<String, String> toBasis(JsonNode node) {
        return mapper.convertValue(node, new TypeReference<LinkedHashMap<String, String>>() {
        });
    }

    private JsonNode read(String relative) throws Exception {
        return mapper.readTree(Files.readString(Path.of(relative), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("preview mock：核验层算出的级别、度量、原因必须与契约文件一致")
    void previewMockMatchesEvaluator() throws Exception {
        TemplateSchema schema = schemaOf(read("mock/template.json"));
        JsonNode data = read("mock/preview-response.json").path("data");
        JsonNode expected = data.path("quality");

        QualityReport actual = evaluator.evaluate(schema,
                toValues(data.path("values")),
                toBasis(data.path("basis")),
                data.path("summary").asText(null),
                data.path("dialogue").asText(),
                NOW);

        assertEquals(expected.path("level").asText(), actual.getLevel().name());

        JsonNode m = expected.path("metrics");
        assertEquals(m.path("fillRate").asDouble(), actual.getMetrics().getFillRate(), 0.0001);
        assertEquals(m.path("requiredRate").asDouble(), actual.getMetrics().getRequiredRate(), 0.0001);
        assertEquals(m.path("optionalCoverage").asDouble(),
                actual.getMetrics().getOptionalCoverage(), 0.0001);
        assertEquals(m.path("basisCoverage").asDouble(),
                actual.getMetrics().getBasisCoverage(), 0.0001);

        assertEquals(mapper.convertValue(expected.path("messages"),
                        new TypeReference<List<String>>() {
                        }),
                actual.getMessages());

        assertEquals(mapper.convertValue(expected.path("decision").path("missingRequired"),
                        new TypeReference<List<String>>() {
                        }),
                actual.getDecision().getMissingRequired());
        assertEquals(mapper.convertValue(expected.path("decision").path("missingOptional"),
                        new TypeReference<List<String>>() {
                        }),
                actual.getDecision().getMissingOptional());
        assertEquals(expected.path("faithfulness").path("coverage").asDouble(),
                actual.getFaithfulness().getCoverage(), 0.0001);

        dump("preview-101", actual);
    }

    @Test
    @DisplayName("必填全缺的场景：红级，且原因句子能解释清楚缺什么")
    void allMissingIsRed() throws Exception {
        TemplateSchema schema = schemaOf(read("mock/template.json"));

        // 对应 SPIKE 里那条「我买的裤子有问题，能不能退？」
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, String> basis = new LinkedHashMap<>();
        for (FieldDef f : schema.getFields()) {
            values.put(f.getKey(), null);
            basis.put(f.getKey(), null);
        }

        QualityReport actual = evaluator.evaluate(schema, values, basis, null,
                "我买的裤子有问题，能不能退？", NOW);

        assertEquals(QualityLevel.RED, actual.getLevel());
        assertEquals(2, actual.getDecision().getBlocking().size());
        assertTrue(actual.getMessages().get(0).startsWith("缺 2 项必填："));
        assertEquals(0.0, actual.getMetrics().getFillRate(), 0.0001);

        dump("missing-102", actual);
    }

    @Test
    @DisplayName("红级工单 mock：核验层的产出 + 编排层追加的一条，边界要对得上")
    void redTicketMockMatchesEvaluator() throws Exception {
        JsonNode tickets = read("mock/ticket.json");
        JsonNode ticket = tickets.get(1);
        JsonNode snapshot = ticket.path("schemaSnapshot");

        List<String> options = new ArrayList<>();
        snapshot.path("decisionOptions").forEach(n -> options.add(n.asText()));
        List<FieldDef> fields = mapper.convertValue(snapshot.path("fields"),
                new TypeReference<List<FieldDef>>() {
                });
        // 核验层不使用 role / knowledge，这里留空即可
        TemplateSchema schema = new TemplateSchema("ticket-102",
                snapshot.path("version").asInt(), "", "", options, fields);

        QualityReport actual = evaluator.evaluate(schema,
                toValues(ticket.path("valuesData")),
                toBasis(ticket.path("basis")),
                ticket.path("summary").asText(null),
                "",   // 全为 null 时依据检查不依赖对话
                NOW);

        JsonNode expected = ticket.path("qualityDetail");
        assertEquals(expected.path("level").asText(), actual.getLevel().name());
        assertEquals(expected.path("decision").path("blocking").size(),
                actual.getDecision().getBlocking().size());
        assertEquals(expected.path("decision").path("degrading").size(),
                actual.getDecision().getDegrading().size());

        List<String> mockMessages = mapper.convertValue(expected.path("messages"),
                new TypeReference<List<String>>() {
                });
        assertEquals(mockMessages.subList(0, actual.getMessages().size()),
                actual.getMessages(),
                "核验层产出的原因句子必须与契约一致");
        assertEquals(1, mockMessages.size() - actual.getMessages().size(),
                "契约里只应多出编排层追加的那一条（信息不全提示）");
        assertTrue(mockMessages.get(mockMessages.size() - 1).contains("信息不全"));

        dump("red-102", actual);
    }
    /** 把握出来的报告落盘，便于人工与 mock 对照 */
    private void dump(String tag, QualityReport report) throws Exception {
        Path dir = Path.of("target", "quality");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(tag + ".json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report),
                StandardCharsets.UTF_8);
    }
}
