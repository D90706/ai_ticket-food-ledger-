package com.example.ticket.chat;

import com.example.ticket.common.BizException;
import com.example.ticket.extraction.ExtractionResult;
import com.example.ticket.extraction.Extractor;
import com.example.ticket.quality.MissingAnalyzer;
import com.example.ticket.quality.MissingReport;
import com.example.ticket.quality.QualityEvaluator;
import com.example.ticket.quality.QualityReport;
import com.example.ticket.template.FieldDef;
import com.example.ticket.template.TemplateSchema;
import com.example.ticket.template.TemplateService;
import com.example.ticket.ticket.TicketFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 全系统唯一的业务分支。
 *
 * 一轮对话的流程（idea.txt 7.1）：
 *   追加消息 → 全量重抽 → 合并到会话 → 判缺失 → 核验 → 追问 / 待确认 / 出单
 *
 * 三条硬规则在代码里：
 *   1 追问次数有上限，超了就带缺口出单（否则工单会永久卡死）
 *   2 缺失必填时，模型的建议一律丢弃（它会在缺信息时自信地给建议）
 *   3 出单后会话关闭，不能继续追问（工单是快照，不是活文档）
 *
 * 关于「顾客确认」：
 *   只对**信息不全**（追问超限）的工单启用。理由：
 *     - 红级本来就是「人工还得回头问顾客」，让顾客先看一眼成本最低
 *     - 绿黄信息够用，多一步就是打扰，而且必然被无脑点过
 *   确认的内容是**一句话摘要**，不是字段表——字段是给我们用的压缩表示，
 *   顾客读的是话。确认卡可编辑，可编辑的文本会迫使人至少扫一眼。
 *
 *   这一步能拿到什么、拿不到什么必须说清楚：
 *     能拿到「语义正确性」信号（顾客是唯一知道原意的人）
 *     但防不住橡皮图章（顾客不看就点），所以分级绝不因此放松，
 *     而是把 customerConfirmed 记在工单上，便于事后对比两类工单的错误率。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationOrchestrator {

    public static final String INFO_INCOMPLETE_NOTICE =
            "追问已达上限，按「信息不全」出单，需人工补齐";

    public static final String CONFIRM_PROMPT =
            "信息还不太完整。我先按理解整理了一版，请确认后再提交给人工。";

    private final SessionStore sessionStore;
    private final TemplateService templateService;
    private final Extractor extractor;
    private final MissingAnalyzer missingAnalyzer;
    private final QualityEvaluator qualityEvaluator;
    private final FollowUpBuilder followUpBuilder;
    private final TicketFactory ticketFactory;
    private final ReturnFlow returnFlow;

    /** 最多追问几次。到上限仍缺必填，就走「待确认」或强制出单。 */
    @Value("${app.chat.max-followups:2}")
    private int maxFollowups = 2;

    /**
     * 信息不全时是否先让顾客确认。
     * 做成开关是为了能现场对比两种流程，而不是为了留后路——
     * 关掉就是旧行为（追问超限直接出单）。
     */
    @Value("${app.chat.confirm-before-ticket:true}")
    private boolean confirmBeforeTicket = true;

    // ============================================================ 一轮对话

    public OrchestratorResult handle(Long sessionId, String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            throw new BizException("消息内容不能为空");
        }

        SessionView session = sessionStore.load(sessionId);
        // 工单被人工打回时，会话要重新打开——顾客还没说完，不该被 409 挡住
        returnFlow.reopenIfReturned(session);
        if (session.getState() == SessionState.TICKETED) {
            throw new BizException(409, "该会话已出单，不能继续发送消息");
        }
        // 待确认时顾客又说了话：当作补充信息，回到对话中重新抽取。
        // 补一句可能就够了，那就直接出单，不必再确认。
        if (session.getState() == SessionState.AWAITING_CONFIRM) {
            session.setState(SessionState.CHATTING);
            session.setDraftSummary(null);
        }

        sessionStore.appendMessage(sessionId, "user", userMessage);
        String dialogue = dialogueText(sessionId);

        TemplateSchema schema = templateService.getSchema(session.getTemplateId());

        ExtractionResult extraction = extractor.extract(schema, dialogue);
        session.merge(extraction.getValues(), extraction.getBasis());

        MissingReport missing = missingAnalyzer.analyze(schema, session.getValues());
        QualityReport quality = qualityEvaluator.evaluate(
                schema, session.getValues(), session.getBasis(),
                extraction.getSummary(), dialogue, LocalDateTime.now());

        if (missing.hasRequiredMissing()) {
            if (session.getFollowupCount() < maxFollowups) {
                return ask(session, schema, missing);
            }
            // 追问到上限仍然缺必填
            if (confirmBeforeTicket) {
                return requestConfirm(session, schema, missing, quality, extraction.getSummary());
            }
            log.info("会话 {} 追问达上限，直接出单（确认开关已关）", sessionId);
            TicketDraft draft = buildDraft(session, schema, extraction.getSummary(),
                    null, quality, "INFO_INCOMPLETE", false);
            return issue(session, draft, quality, true);
        }

        TicketDraft draft = buildDraft(session, schema, extraction.getSummary(),
                extraction.getSuggestion(), quality, "PENDING", false);
        return issue(session, draft, quality, false);
    }

    /**
     * 顾客点了「确认提交」。
     *
     * 只在待确认状态下有效。摘要允许被顾客改过——
     * 改过的那句话就是工单的 summary，也是人工看到的第一行字。
     */
    public OrchestratorResult confirm(Long sessionId, String editedSummary) {
        SessionView session = sessionStore.load(sessionId);
        if (session.getState() != SessionState.AWAITING_CONFIRM) {
            throw new BizException(409, "当前会话不在待确认状态，无需确认");
        }

        TemplateSchema schema = templateService.getSchema(session.getTemplateId());
        String dialogue = dialogueText(sessionId);

        String summary = (editedSummary == null || editedSummary.isBlank())
                ? session.getDraftSummary()
                : editedSummary.trim();
        if (summary == null || summary.isBlank()) {
            summary = fallbackSummary(schema, session);
        }

        QualityReport quality = qualityEvaluator.evaluate(
                schema, session.getValues(), session.getBasis(),
                summary, dialogue, LocalDateTime.now());

        TicketDraft draft = buildDraft(session, schema, summary, null,
                quality, "INFO_INCOMPLETE", true);
        log.info("会话 {} 顾客已确认，出单", sessionId);
        return issue(session, draft, quality, true);
    }

    // ============================================================ 追问

    private OrchestratorResult ask(SessionView session, TemplateSchema schema, MissingReport missing) {
        String question = followUpBuilder.build(schema, missing.required(),
                session.getFollowupCount());

        session.setFollowupCount(session.getFollowupCount() + 1);
        sessionStore.appendMessage(session.getId(), "assistant", question);
        sessionStore.save(session);

        log.debug("会话 {} 第 {} 次追问：缺 {}", session.getId(),
                session.getFollowupCount(), missing.required());
        return OrchestratorResult.ask(question, session.getFollowupCount());
    }

    // ============================================================ 待确认

    private OrchestratorResult requestConfirm(SessionView session, TemplateSchema schema,
                                              MissingReport missing, QualityReport quality,
                                              String summary) {
        String display = (summary == null || summary.isBlank())
                ? fallbackSummary(schema, session)
                : summary.trim();

        session.setState(SessionState.AWAITING_CONFIRM);
        session.setDraftSummary(display);
        sessionStore.appendMessage(session.getId(), "assistant", CONFIRM_PROMPT);
        sessionStore.save(session);

        log.info("会话 {} 转为待确认（缺 {} 项必填）", session.getId(), missing.required().size());
        return OrchestratorResult.confirm(display, labelsOf(schema, missing.required()),
                quality.getLevel().name());
    }

    // ============================================================ 出单

    private OrchestratorResult issue(SessionView session, TicketDraft draft,
                                     QualityReport quality, boolean infoIncomplete) {
        List<String> messages = new ArrayList<>(quality.getMessages());
        if (infoIncomplete) {
            messages.add(INFO_INCOMPLETE_NOTICE);
        }

        // 打回补全的第二次提交：不新建工单，原地更新那一张
        boolean resubmit = session.getTicketId() != null;
        Long ticketId;
        if (resubmit) {
            ticketFactory.supplement(session.getTicketId(), draft);
            ticketId = session.getTicketId();
        } else {
            // 先落库再关闭会话：如果落库失败，会话仍可继续，顾客不至于白说一遍
            ticketId = ticketFactory.publish(draft);
        }

        session.setState(SessionState.TICKETED);
        session.setTicketId(ticketId);
        session.setDraftSummary(null);
        sessionStore.appendMessage(session.getId(), "assistant",
                resubmit
                        ? "信息已补全，工单 #" + ticketId + " 已重新提交给人工。"
                        : followUpBuilder.ticketNotice(levelLabel(quality)));
        sessionStore.save(session);

        return OrchestratorResult.ticket(draft, ticketId,
                quality.getLevel().name(), messages, resubmit);
    }

    private TicketDraft buildDraft(SessionView session, TemplateSchema schema, String summary,
                                   String suggestion, QualityReport quality,
                                   String status, boolean confirmed) {
        TicketDraft draft = new TicketDraft();
        draft.setSessionId(session.getId());
        draft.setTemplateId(session.getTemplateId());
        draft.setTemplateVersion(schema.getVersion());
        draft.setFields(schema.getFields());
        draft.setDecisionOptions(schema.getDecisionOptions());
        draft.setValues(new LinkedHashMap<>(session.getValues()));
        draft.setBasis(new LinkedHashMap<>(session.getBasis()));
        draft.setSummary(summary);

        // 规则 2：缺失必填时，无论模型给了什么建议都丢弃。
        // 实测模型会在缺必填项时自信地给出「远程处理」这类建议。
        draft.setSuggestion(suggestion);
        draft.setQuality(quality);
        draft.setStatus(status);
        draft.setCustomerConfirmed(confirmed);
        return draft;
    }

    // ============================================================ 工具

    /** 只把顾客说的话拼成对话文本——追问是我们自己写的，不该参与抽取 */
    private String dialogueText(Long sessionId) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : sessionStore.history(sessionId)) {
            if (!"user".equals(m.getRole())) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(m.getContent());
        }
        return sb.toString();
    }

    /**
     * 模型没给摘要时的兜底：用已抽到的字段拼一句。
     * 这不是编造——只是把已经抽到的值摆成一句话，让顾客有东西可核对。
     */
    private String fallbackSummary(TemplateSchema schema, SessionView session) {
        List<String> parts = new ArrayList<>();
        for (FieldDef field : schema.getFields()) {
            Object value = session.getValues().get(field.getKey());
            if (value == null) {
                continue;
            }
            String label = field.getLabel() == null || field.getLabel().isBlank()
                    ? field.getKey() : field.getLabel();
            parts.add(label + "：" + value);
        }
        return parts.isEmpty() ? "（没有整理出有效信息，请在下方补充）" : String.join("；", parts);
    }

    private List<String> labelsOf(TemplateSchema schema, List<String> keys) {
        List<String> labels = new ArrayList<>(keys.size());
        for (String key : keys) {
            labels.add(schema.find(key)
                    .map(f -> f.getLabel() == null || f.getLabel().isBlank() ? key : f.getLabel())
                    .orElse(key));
        }
        return labels;
    }

    private String levelLabel(QualityReport quality) {
        return switch (quality.getLevel()) {
            case RED -> "信息不全";
            case YELLOW -> "待核实";
            case GREEN -> "可直接处理";
        };
    }
}
