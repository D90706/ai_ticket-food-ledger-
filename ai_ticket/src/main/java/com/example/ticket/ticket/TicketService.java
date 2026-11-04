package com.example.ticket.ticket;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.ticket.common.BizException;
import com.example.ticket.quality.QualityLevel;
import com.example.ticket.ticket.dto.TicketDetail;
import com.example.ticket.ticket.dto.TicketPatchRequest;
import com.example.ticket.ticket.dto.TicketSummary;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 工单层的业务：查询、状态流转、决策记录。
 *
 * 边界（design-modules.md §七）：
 *   做：状态机、指派、决策、把 JSON 列解成结构化对象
 *   不做：不调模型、不重新抽取、不改 values
 *
 * 最后一条尤其重要：工单是快照，不是活文档。
 * 人工要修正内容，走「编辑工单」，而不是让 AI 重抽一遍——
 * 重抽会得到一张不同的工单，而工单必须可追溯。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TicketService {

    /** 同一张工单最多被打回几次。防止「人工踢给顾客、顾客又没补全」的死循环 */
    public static final int MAX_RETURNS = 2;

    private final TicketMapper ticketMapper;
    private final ObjectMapper mapper;

    // ---------------------------------------------------------- 查询

    public List<TicketSummary> list(String status, String level) {
        var query = Wrappers.<Ticket>lambdaQuery().orderByDesc(Ticket::getId);
        if (status != null && !status.isBlank()) {
            query.eq(Ticket::getStatus, parseStatus(status));
        }
        if (level != null && !level.isBlank()) {
            query.eq(Ticket::getQualityLevel, parseLevel(level));
        }
        return ticketMapper.selectList(query).stream().map(this::toSummary).toList();
    }

    public TicketDetail get(Long id) {
        return toDetail(require(id));
    }

    // ---------------------------------------------------------- 更新

    /** 兼容无登录上下文的调用（单测、脚本），不带审计人 */
    @Transactional
    public TicketDetail patch(Long id, TicketPatchRequest request) {
        return patch(id, request, null);
    }

    /**
     * 更新工单。
     *
     * actor 是当前登录客服的显示名，用来：
     *   1 记 last_actor（审计：这张单最后是谁动的）
     *   2 首次决策且没人认领时自动填 assignee（谁处理谁负责）
     *
     * 并发保护是两层：
     *   先比版本给一句人话（409 + 当前版本号），
     *   再靠 SQL 的 WHERE version = ? 挡住「比较与更新之间」那几毫秒的竞态。
     * 只有第一层是 check-then-act，靠第二层兜底。
     */
    @Transactional
    public TicketDetail patch(Long id, TicketPatchRequest request, String actor) {
        Ticket ticket = require(id);

        Integer clientVersion = request.getVersion();
        if (clientVersion == null) {
            throw new BizException(400, "缺少 version 字段，拒绝无锁更新");
        }
        if (!clientVersion.equals(ticket.getVersion())) {
            throw new BizException(409, "这张工单已被他人修改（你看到的是 v" + clientVersion
                    + "，当前是 v" + ticket.getVersion() + "），请刷新后重试");
        }

        String previousStatus = ticket.getStatus();
        if (request.getStatus() != null && !request.getStatus().isBlank()) {
            TicketStatus current = TicketStatus.valueOf(ticket.getStatus());
            TicketStatus next = parseStatusEnum(request.getStatus());
            if (next != current) {
                if (!current.canTransitionTo(next)) {
                    throw new BizException("工单状态不能从 " + current
                            + " 变为 " + next + "，合法目标：" + current.nextStates());
                }
                ticket.setStatus(next.name());
                if (next.isDecided() && ticket.getDecidedAt() == null) {
                    LocalDateTime now = LocalDateTime.now();
                    ticket.setDecidedAt(now);
                    if (ticket.getCreatedAt() != null) {
                        // 夹到 0 以上：数据库时间与 JVM 时间不保证同源，
                        // 一个负的耗时比没有耗时更糟——它会让报表看起来像 bug。
                        long ms = Duration.between(ticket.getCreatedAt(), now).toMillis();
                        ticket.setDecisionTimeMs((int) Math.max(0, ms));
                    }
                }
            }
        }
        if (request.getAssignee() != null && !request.getAssignee().isBlank()) {
            ticket.setAssignee(request.getAssignee());
        } else if (actor != null && (ticket.getAssignee() == null || ticket.getAssignee().isBlank())) {
            // 谁处理谁负责：没人认领时，由第一个动手的人自动认领。
            // 显式传入的 assignee 优先，不会被覆盖。
            ticket.setAssignee(actor);
        }
        if (request.getDecision() != null) {
            ticket.setDecision(request.getDecision());
        }
        if (request.getNeedsClarify() != null) {
            ticket.setNeedsClarify(request.getNeedsClarify());
        }

        // 打回：封顶检查 + 次数累加 + 记下人工要说的话
        if (TicketStatus.RETURNED.name().equals(ticket.getStatus())
                && !TicketStatus.RETURNED.name().equals(previousStatus)) {
            int used = ticket.getReturnCount() == null ? 0 : ticket.getReturnCount();
            if (used >= MAX_RETURNS) {
                throw new BizException("这张工单已经打回 " + used + " 次，请人工直接处理或关闭");
            }
            ticket.setReturnCount(used + 1);
            if (request.getReturnNote() != null) {
                ticket.setReturnNote(request.getReturnNote().trim());
            }
        }

        if (actor != null) {
            ticket.setLastActor(actor);
        }
        // 让乐观锁拦截器带上 WHERE version = 客户端版本
        ticket.setVersion(clientVersion);

        int rows = ticketMapper.updateById(ticket);
        if (rows == 0) {
            // 第二层：SELECT 与 UPDATE 之间被改了
            throw new BizException(409, "这张工单刚刚被他人修改，请刷新后重试");
        }
        log.info("工单 {} 更新为 status={} assignee={} decision={} needsClarify={} by={}",
                id, ticket.getStatus(), ticket.getAssignee(),
                ticket.getDecision(), ticket.getNeedsClarify(), actor);
        return toDetail(ticket);
    }

    // ---------------------------------------------------------- 转换

    private Ticket require(Long id) {
        Ticket ticket = ticketMapper.selectById(id);
        if (ticket == null) {
            throw BizException.notFound("工单 " + id + " ");
        }
        return ticket;
    }

    private TicketSummary toSummary(Ticket t) {
        TicketSummary s = new TicketSummary();
        s.setId(t.getId());
        s.setTemplateId(t.getTemplateId());
        s.setQualityLevel(t.getQualityLevel());
        s.setStatus(t.getStatus());
        s.setAssignee(t.getAssignee());
        s.setSummary(t.getSummary());
        s.setNeedsClarify(t.getNeedsClarify());
        s.setCreatedAt(t.getCreatedAt());
        return s;
    }

    private TicketDetail toDetail(Ticket t) {
        TicketDetail d = new TicketDetail();
        d.setId(t.getId());
        d.setTemplateId(t.getTemplateId());
        d.setTemplateVersion(t.getTemplateVersion());
        d.setSchemaSnapshot(readMap(t.getSchemaSnapshotJson()));
        d.setValues(readMap(t.getValuesJson()));
        d.setBasis(readStringMap(t.getBasis()));
        d.setSummary(t.getSummary());
        d.setSuggestion(t.getSuggestion());
        d.setQualityLevel(t.getQualityLevel());
        d.setQualityDetail(readMap(t.getQualityDetailJson()));
        d.setStatus(t.getStatus());
        d.setAssignee(t.getAssignee());
        d.setDecision(t.getDecision());
        d.setSessionId(t.getSessionId());
        d.setNeedsClarify(t.getNeedsClarify());
        d.setCustomerConfirmed(t.getCustomerConfirmed());
        d.setVersion(t.getVersion());
        d.setLastActor(t.getLastActor());
        d.setRevision(t.getRevision());
        d.setReturnCount(t.getReturnCount());
        d.setReturnNote(t.getReturnNote());
        d.setDecisionTimeMs(t.getDecisionTimeMs());
        d.setCreatedAt(t.getCreatedAt());
        d.setDecidedAt(t.getDecidedAt());
        return d;
    }

    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new BizException(500, "工单数据损坏，无法解析: " + e.getMessage());
        }
    }

    private Map<String, String> readStringMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<Map<String, String>>() {
            });
        } catch (Exception e) {
            throw new BizException(500, "工单依据数据损坏，无法解析: " + e.getMessage());
        }
    }

    /** 查询条件用：返回规范化后的状态名 */
    private String parseStatus(String status) {
        return parseStatusEnum(status).name();
    }

    private TicketStatus parseStatusEnum(String status) {
        try {
            return TicketStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BizException("未知的工单状态: " + status);
        }
    }

    private String parseLevel(String level) {
        try {
            return QualityLevel.valueOf(level.trim().toUpperCase()).name();
        } catch (IllegalArgumentException e) {
            throw new BizException("未知的工单级别: " + level);
        }
    }
}
