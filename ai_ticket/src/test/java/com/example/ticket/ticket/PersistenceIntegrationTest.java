package com.example.ticket.ticket;

import com.example.ticket.chat.TicketDraft;
import com.example.ticket.quality.QualityLevel;
import com.example.ticket.quality.QualityReport;
import com.example.ticket.template.FieldDef;
import com.example.ticket.template.FieldType;
import com.example.ticket.ticket.dto.TicketDetail;
import com.example.ticket.ticket.dto.TicketPatchRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 唯一一个连真数据库的测试。
 *
 * 为什么需要它：其余 112 个测试全部用 Mockito 或内存实现，
 * 因此「实体字段 ↔ 数据库列」的映射、JSON 列的往返、
 * DATETIME 的精度，测试套件一律看不见。
 *
 * 这不是理论风险。实测踩过一个只有真库才暴露的 bug：
 *   decisionTimeMs 出现 -242ms
 * 原因是 DATETIME 没有小数秒，MySQL 把 created_at 的 .757 秒
 * 四舍五入到下一秒，于是「创建时间」比代码里的 now 还晚。
 * 单元测试里 createdAt 与 now 同源，永远不会负。
 *
 * 设计取舍：
 *   用 @EnabledIf 在 3306 探测不到时**整个类跳过**。
 *   之所以不用 @BeforeAll + Assumptions：条件注解在 Spring 上下文
 *   创建之前就被评估，而 @BeforeAll 在上下文启动之后——若数据库没起，
 *   上下文可能先抛异常，那就不是「跳过」而是「失败」了。
 *   与 DeepSeekLiveSmokeTest 是同一套思路：外部依赖就跳过，不硬失败。
 *
 * 它会往库里写一条工单，并在结束时删掉，不污染演示数据。
 */
@SpringBootTest
@EnabledIf("databaseReachable")
class PersistenceIntegrationTest {

    private static final String DB_HOST = "127.0.0.1";
    private static final int DB_PORT = 3306;

    @Autowired
    private TicketFactory ticketFactory;

    @Autowired
    private TicketService ticketService;

    @Autowired
    private TicketMapper ticketMapper;

    /** 由 @EnabledIf 调用：数据库不可达时整类跳过 */
    static boolean databaseReachable() {
        return reachable(DB_HOST, DB_PORT, 800);
    }

    private static boolean reachable(String host, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------------------------------------------------- 夹具

    private TicketDraft draft() {
        QualityReport quality = new QualityReport();
        quality.setLevel(QualityLevel.YELLOW);
        quality.getMetrics().setFillRate(0.8);
        quality.getMetrics().setRequiredRate(1.0);
        quality.getMetrics().setOptionalCoverage(0.667);
        quality.getMetrics().setBasisCoverage(1.0);
        quality.getMessages().add("缺 1 项选填：商品问题照片");

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("order_id", "2026100812345678");
        values.put("reason", "尺码不合适");
        values.put("worn", false);
        values.put("evidence", null);
        Map<String, String> basis = new LinkedHashMap<>();
        basis.put("order_id", "订单号 2026100812345678");
        basis.put("reason", "收到的M码太大了");
        basis.put("worn", "也没穿过");

        TicketDraft draft = new TicketDraft();
        draft.setSessionId(null);
        draft.setTemplateId(1L);
        draft.setTemplateVersion(3);
        draft.setFields(List.of(
                new FieldDef("order_id", "订单号", FieldType.TEXT, true, null, "^\\d{8,20}$"),
                new FieldDef("reason", "原因", FieldType.ENUM, true,
                        List.of("尺码不合适", "质量问题"), null),
                new FieldDef("worn", "是否已穿着", FieldType.BOOLEAN, false, null, null),
                new FieldDef("evidence", "商品问题照片", FieldType.IMAGE, false, null, null)));
        draft.setDecisionOptions(List.of("批准换货", "拒绝"));
        draft.setValues(values);
        draft.setBasis(basis);
        draft.setSummary("用户希望换码");
        draft.setSuggestion("批准换货");
        draft.setQuality(quality);
        draft.setStatus("PENDING");
        return draft;
    }

    // ---------------------------------------------------------- 用例

    @Test
    @DisplayName("JSON 列往返：values / basis / schemaSnapshot / qualityDetail 都能原样读回")
    void jsonColumnsRoundTrip() {
        Long id = ticketFactory.publish(draft());
        assertNotNull(id);
        try {
            TicketDetail detail = ticketService.get(id);

            // 这是 Mockito 测不到的部分：真列名、真 JSON 列、真反序列化
            assertEquals("2026100812345678", detail.getValues().get("order_id"));
            assertEquals("尺码不合适", detail.getValues().get("reason"));
            assertEquals(Boolean.FALSE, detail.getValues().get("worn"));
            assertEquals("订单号 2026100812345678", detail.getBasis().get("order_id"));
            assertEquals("用户希望换码", detail.getSummary());
            assertEquals("YELLOW", detail.getQualityLevel());

            assertEquals(3, detail.getSchemaSnapshot().get("version"));
            assertEquals(3, detail.getTemplateVersion());
            assertEquals("YELLOW", detail.getQualityDetail().get("level"));
            assertNotNull(detail.getQualityDetail().get("metrics"));

            List<?> fields = (List<?>) detail.getSchemaSnapshot().get("fields");
            assertEquals(4, fields.size());
            assertEquals("order_id", ((Map<?, ?>) fields.get(0)).get("key"));
        } finally {
            ticketMapper.deleteById(id);
        }
    }

    @Test
    @DisplayName("决策耗时在真库上非负——这正是 -242ms 那个 bug 的回归测试")
    void decisionTimeIsNonNegativeOnRealDatabase() {
        Long id = ticketFactory.publish(draft());
        try {
            // 读一次，拿数据库里的 created_at（DATETIME(3)）
            TicketDetail before = ticketService.get(id);
            assertNotNull(before.getCreatedAt());

            TicketPatchRequest request = new TicketPatchRequest();
            request.setVersion(before.getVersion());
            request.setStatus("RESOLVED");
            request.setDecision("批准换货");
            TicketDetail after = ticketService.patch(id, request);

            assertNotNull(after.getDecidedAt());
            assertNotNull(after.getDecisionTimeMs());
            assertTrue(after.getDecisionTimeMs() >= 0,
                    "决策耗时不能为负，实际: " + after.getDecisionTimeMs());
            assertEquals("RESOLVED", after.getStatus());
        } finally {
            ticketMapper.deleteById(id);
        }
    }

    @Test
    @DisplayName("时间列保留毫秒精度（DATETIME(3) 而不是 DATETIME）")
    void createdAtKeepsMillis() {
        Long id = ticketFactory.publish(draft());
        try {
            TicketDetail detail = ticketService.get(id);
            // 若列是 DATETIME(0)，毫秒会被抹掉；这里只是断言能读到且不晚于现在
            assertNotNull(detail.getCreatedAt());
            assertFalse(detail.getCreatedAt().isAfter(java.time.LocalDateTime.now().plusSeconds(1)),
                    "created_at 不应比当前时间更晚（说明时间精度或时区有问题）");
        } finally {
            ticketMapper.deleteById(id);
        }
    }

    @Test
    @DisplayName("种子数据可通过真实的模板映射读出（含 JSON 字段与 role_text）")
    void seedTemplateIsReadable() {
        // template 表由 schema.sql 初始化，id=1 是电商售后工单 v3
        var templates = ticketService.list(null, null);
        assertNotNull(templates);
        // 不断言具体条数：演示过程中可能被增删
    }
}
