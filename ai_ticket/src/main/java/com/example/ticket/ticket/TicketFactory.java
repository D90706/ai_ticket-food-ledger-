package com.example.ticket.ticket;

import com.example.ticket.chat.TicketDraft;
import com.example.ticket.common.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把工单草稿落成真实工单。
 *
 * 它是「编排层」与「工单层」之间唯一的通道：
 * 编排层备齐材料，这里负责序列化与写入，并把 id 交回去。
 *
 * 两件必须在写入时固化的事：
 *   schema 快照 —— 工单永远带着自己那版 schema 渲染
 *   质量明细   —— 存三维度，不只是结论；否则以后没法解释「为什么是红」
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketFactory {

    private final TicketMapper ticketMapper;
    private final ObjectMapper mapper;

    public Long publish(TicketDraft draft) {
        if (draft == null || draft.getQuality() == null) {
            throw new BizException(500, "工单草稿不完整，缺少核验结果");
        }

        Ticket ticket = new Ticket();
        ticket.setTemplateId(draft.getTemplateId());
        ticket.setTemplateVersion(draft.getTemplateVersion());
        ticket.setSchemaSnapshotJson(write(snapshotOf(draft)));
        ticket.setValuesJson(write(draft.getValues()));
        ticket.setBasis(write(draft.getBasis()));
        ticket.setSummary(draft.getSummary());
        ticket.setSuggestion(draft.getSuggestion());
        ticket.setQualityLevel(draft.getQuality().getLevel().name());
        ticket.setQualityDetailJson(write(draft.getQuality()));
        ticket.setStatus(normalizeStatus(draft.getStatus()));
        ticket.setSessionId(draft.getSessionId());
        ticket.setNeedsClarify(draft.infoIncomplete());
        ticket.setCustomerConfirmed(draft.isCustomerConfirmed());
        ticket.setCreatedAt(LocalDateTime.now());

        ticketMapper.insert(ticket);
        log.info("工单已生成 id={} level={} status={} 缺必填={}",
                ticket.getId(), ticket.getQualityLevel(), ticket.getStatus(),
                draft.infoIncomplete());
        return ticket.getId();
    }

    /**
     * 补全被打回的工单：原地更新内容，修订号 +1，状态回到 PENDING。
     *
     * 这是「工单是快照」这条原则的唯一例外，也是它的一次修订：
     * 快照的目的是可追溯，不是不可变。可追溯由三件事保住——
     *   revision 每改一次 +1
     *   last_actor 记下是谁触发的
     *   乐观锁 version 保证不会两个人同时改
     *
     * 只允许 RETURNED 状态进来：一张正常的工单不该被悄悄改写。
     */
    public int supplement(Long ticketId, TicketDraft draft) {
        if (draft == null || draft.getQuality() == null) {
            throw new BizException(500, "工单草稿不完整，缺少核验结果");
        }
        Ticket ticket = ticketMapper.selectById(ticketId);
        if (ticket == null) {
            throw BizException.notFound("工单 " + ticketId + " ");
        }
        if (!TicketStatus.RETURNED.name().equals(ticket.getStatus())) {
            throw new BizException(409, "只有被打回的工单可以补全，当前状态：" + ticket.getStatus());
        }

        // 内容整体替换：schema 快照沿用生成时那一版
        ticket.setValuesJson(write(draft.getValues()));
        ticket.setBasis(write(draft.getBasis()));
        ticket.setSummary(draft.getSummary());
        ticket.setSuggestion(draft.getSuggestion());
        ticket.setQualityLevel(draft.getQuality().getLevel().name());
        ticket.setQualityDetailJson(write(draft.getQuality()));
        ticket.setCustomerConfirmed(draft.isCustomerConfirmed());
        ticket.setStatus(TicketStatus.PENDING.name());
        ticket.setRevision((ticket.getRevision() == null ? 1 : ticket.getRevision()) + 1);

        int rows = ticketMapper.updateById(ticket);
        if (rows == 0) {
            throw new BizException(409, "工单刚刚被他人修改，请刷新后重试");
        }
        log.info("工单 {} 已补全并重新提交，revision={} level={}",
                ticketId, ticket.getRevision(), ticket.getQualityLevel());
        return ticket.getRevision();
    }

    /**
     * schema 快照：只保留渲染与追溯需要的东西。
     * 不存 role / knowledge —— 它们对「这张工单长什么样」没有影响，
     * 存进去只会让快照变重。
     */
    private Map<String, Object> snapshotOf(TicketDraft draft) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("version", draft.getTemplateVersion());
        snapshot.put("decisionOptions", draft.getDecisionOptions());
        snapshot.put("fields", draft.getFields());
        return snapshot;
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return TicketStatus.PENDING.name();
        }
        try {
            return TicketStatus.valueOf(status).name();
        } catch (IllegalArgumentException e) {
            throw new BizException(500, "未知的工单状态: " + status);
        }
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BizException(500, "工单序列化失败: " + e.getMessage());
        }
    }
}
