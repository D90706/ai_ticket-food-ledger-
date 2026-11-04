package com.example.ticket.ticket;

import com.example.ticket.common.BizException;
import com.example.ticket.ticket.dto.TicketDetail;
import com.example.ticket.ticket.dto.TicketPatchRequest;
import com.example.ticket.ticket.dto.TicketSummary;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 工单服务的单元测试。
 *
 * 覆盖两件最容易出错的事：
 *   状态流转必须被状态机拦住（不能随手改成任意状态）
 *   第一次进入「已解决/已关闭」时才写 decided_at 与决策耗时
 */
class TicketServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final TicketMapper ticketMapper = mock(TicketMapper.class);
    private final TicketService service = new TicketService(ticketMapper, mapper);

    private Ticket ticket;

    @BeforeEach
    void setUp() {
        ticket = new Ticket();
        ticket.setId(1L);
        ticket.setTemplateId(1L);
        ticket.setTemplateVersion(3);
        ticket.setStatus(TicketStatus.PENDING.name());
        ticket.setQualityLevel("YELLOW");
        ticket.setSchemaSnapshotJson("{\"version\":3}");
        ticket.setValuesJson("{\"order_id\":\"2026100812345678\"}");
        ticket.setBasis("{\"order_id\":\"订单号 2026100812345678\"}");
        ticket.setQualityDetailJson("{\"level\":\"YELLOW\"}");
        ticket.setSummary("用户希望换码");
        ticket.setNeedsClarify(false);
        ticket.setVersion(0);
        ticket.setCreatedAt(LocalDateTime.now().minusSeconds(30));
        when(ticketMapper.selectById(1L)).thenReturn(ticket);
        when(ticketMapper.updateById(any(Ticket.class))).thenReturn(1);
    }

    private TicketPatchRequest status(String s) {
        TicketPatchRequest r = new TicketPatchRequest();
        r.setStatus(s);
        r.setVersion(0);   // 与夹具一致
        return r;
    }

    @Test
    @DisplayName("打回：次数累加，并记下人工留的话")
    void returnIncrementsCount() {
        ticket.setStatus(TicketStatus.ASSIGNED.name());
        ticket.setReturnCount(0);

        TicketPatchRequest r = status("RETURNED");
        r.setReturnNote("麻烦提供订单号");

        TicketDetail d = service.patch(1L, r, "张三");
        assertEquals(1, d.getReturnCount());
        assertEquals("麻烦提供订单号", d.getReturnNote());
    }

    @Test
    @DisplayName("打回封顶：已经打回 2 次就不再允许打回")
    void returnCapped() {
        ticket.setStatus(TicketStatus.ASSIGNED.name());
        ticket.setReturnCount(TicketService.MAX_RETURNS);

        BizException e = assertThrows(BizException.class,
                () -> service.patch(1L, status("RETURNED"), "张三"));
        assertEquals("这张工单已经打回 2 次，请人工直接处理或关闭", e.getMessage());
    }

    @Test
    @DisplayName("乐观锁：版本对不上就拒绝，并说清当前是第几版")
    void staleVersionRejected() {
        TicketPatchRequest r = status("ASSIGNED");
        r.setVersion(7);   // 客户端拿的是旧版本

        BizException e = assertThrows(BizException.class, () -> service.patch(1L, r));
        assertEquals(409, e.getCode());
        assertTrue(e.getMessage().contains("v7"), e.getMessage());
        assertTrue(e.getMessage().contains("v0"), e.getMessage());
    }

    @Test
    @DisplayName("乐观锁：版本一致才写入")
    void freshVersionAccepted() {
        TicketPatchRequest r = status("ASSIGNED");
        r.setVersion(0);

        TicketDetail d = service.patch(1L, r);
        assertEquals("ASSIGNED", d.getStatus());
    }

    @Test
    @DisplayName("乐观锁：不传 version 直接拒绝，不接受无锁更新")
    void missingVersionRejected() {
        TicketPatchRequest r = new TicketPatchRequest();
        r.setStatus("ASSIGNED");

        BizException e = assertThrows(BizException.class, () -> service.patch(1L, r));
        assertEquals(400, e.getCode());
    }

    @Test
    @DisplayName("乐观锁：SQL 那边影响 0 行（比较与更新之间被改）也要 409")
    void zeroRowsMeansConflict() {
        when(ticketMapper.updateById(any(Ticket.class))).thenReturn(0);

        BizException e = assertThrows(BizException.class,
                () -> service.patch(1L, status("ASSIGNED")));
        assertEquals(409, e.getCode());
    }

    @Test
    @DisplayName("审计：最后修改者被记下来；没人认领时自动认领")
    void actorRecordedAndAutoClaimed() {
        TicketDetail d = service.patch(1L, status("ASSIGNED"), "李四");

        assertEquals("李四", d.getLastActor());
        assertEquals("李四", d.getAssignee(), "没人认领时由第一个动手的人认领");
    }

    @Test
    @DisplayName("审计：显式指定的负责人不会被登录者覆盖")
    void explicitAssigneeWins() {
        TicketPatchRequest r = status("ASSIGNED");
        r.setAssignee("王五");

        TicketDetail d = service.patch(1L, r, "李四");
        assertEquals("王五", d.getAssignee());
        assertEquals("李四", d.getLastActor());
    }

    @Test
    @DisplayName("合法流转：待处理 → 已指派")
    void validTransition() {
        TicketDetail d = service.patch(1L, status("ASSIGNED"));
        assertEquals("ASSIGNED", d.getStatus());
    }

    @Test
    @DisplayName("非法流转被拦下，并提示合法目标")
    void invalidTransition() {
        ticket.setStatus(TicketStatus.CLOSED.name());

        BizException e = assertThrows(BizException.class,
                () -> service.patch(1L, status("PROCESSING")));
        assertTrue(e.getMessage().contains("不能从"), e.getMessage());
        assertTrue(e.getMessage().contains("PENDING"), "应提示合法目标");
    }

    @Test
    @DisplayName("进入已解决时写入 decided_at 与决策耗时")
    void decidedAtAndDuration() {
        TicketDetail d = service.patch(1L, status("RESOLVED"));

        assertNotNull(d.getDecidedAt());
        assertNotNull(d.getDecisionTimeMs());
        assertTrue(d.getDecisionTimeMs() >= 30_000, "应约为 30 秒: " + d.getDecisionTimeMs());
    }

    @Test
    @DisplayName("数据库时间晚于 JVM 时间时，耗时不出现负数")
    void decisionTimeNeverNegative() {
        // 复现真实故障：DATETIME 没有小数秒，MySQL 会把 created_at
        // 四舍五入到下一秒，于是「创建时间」比 now 还晚。
        ticket.setCreatedAt(LocalDateTime.now().plusSeconds(1));

        TicketDetail d = service.patch(1L, status("RESOLVED"));
        assertEquals(0, d.getDecisionTimeMs(), "负耗时必须被夹到 0");
    }
    @Test
    @DisplayName("重复设置同一状态不报错，也不覆盖已有的 decided_at")
    void sameStatusIsIdempotent() {
        service.patch(1L, status("RESOLVED"));
        LocalDateTime first = ticket.getDecidedAt();

        service.patch(1L, status("RESOLVED"));
        assertEquals(first, ticket.getDecidedAt());
    }

    @Test
    @DisplayName("指派、决策、回问埋点可以单独更新")
    void patchIndividualFields() {
        TicketPatchRequest r = new TicketPatchRequest();
        r.setVersion(0);
        r.setAssignee("张三");
        r.setDecision("批准换货");
        r.setNeedsClarify(true);

        TicketDetail d = service.patch(1L, r);
        assertEquals("张三", d.getAssignee());
        assertEquals("批准换货", d.getDecision());
        assertTrue(d.getNeedsClarify());
        assertEquals("PENDING", d.getStatus(), "未传状态时不应改变状态");
    }

    @Test
    @DisplayName("详情把 JSON 列解开成结构化对象")
    void detailParsesJson() {
        TicketDetail d = service.get(1L);
        assertEquals(3, d.getSchemaSnapshot().get("version"));
        assertEquals("2026100812345678", d.getValues().get("order_id"));
        assertEquals("订单号 2026100812345678", d.getBasis().get("order_id"));
        assertEquals("YELLOW", d.getQualityDetail().get("level"));
    }

    @Test
    @DisplayName("列表按状态与级别过滤")
    void listFilters() {
        when(ticketMapper.selectList(any())).thenReturn(List.of(ticket));

        List<TicketSummary> all = service.list(null, null);
        assertEquals(1, all.size());
        assertEquals("YELLOW", all.get(0).getQualityLevel());

        // 非法参数要被翻译成业务异常，而不是 500
        assertThrows(BizException.class, () -> service.list("不存在的状态", null));
        assertThrows(BizException.class, () -> service.list(null, "紫色"));
        assertEquals(1, service.list("pending", "yellow").size(), "大小写应兼容");
    }

    @Test
    @DisplayName("工单不存在时 404")
    void notFound() {
        when(ticketMapper.selectById(99L)).thenReturn(null);
        BizException e = assertThrows(BizException.class, () -> service.get(99L));
        assertEquals(404, e.getCode());
    }
}
