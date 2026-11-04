package com.example.ticket.chat;

import com.example.ticket.quality.MissingAnalyzer;
import com.example.ticket.quality.MissingReport;
import com.example.ticket.template.TemplateSchema;
import com.example.ticket.template.TemplateService;
import com.example.ticket.ticket.Ticket;
import com.example.ticket.ticket.TicketMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 打回后的「重开会话」。
 *
 * 人工把工单打回（status = RETURNED）之后，顾客要能继续说话。
 * 但那张单已经出过，会话也是 TICKETED——所以这里负责把会话重新打开，
 * 并把「需要补充什么」问出去。
 *
 * 为什么放在 chat 包而不是 ticket 包：
 *   chat 已经依赖 ticket（TicketFactory / TicketMapper），
 *   反过来让 ticket 依赖 chat 就成环了。
 *   而且「问顾客」本来就是对话层的事，不是工单状态机的事。
 *
 * 为什么懒触发（顾客来轮询 / 来发消息时才重开）：
 *   重开的唯一意义是让顾客看到追问。顾客不在，写了也没人看，
 *   顺带省掉一个定时任务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReturnFlow {

    public static final String RETURNED = "RETURNED";

    private final SessionStore sessionStore;
    private final TicketMapper ticketMapper;
    private final TemplateService templateService;
    private final MissingAnalyzer missingAnalyzer;

    /**
     * 如果工单被打回且会话还没重开，就重开一次。
     *
     * 判据只有一个：会话还是 TICKETED。重开之后状态变 CHATTING，
     * 所以后续调用自然不会再写一遍追问——不需要额外的标记位。
     *
     * @return true 表示这次刚刚重开
     */
    public boolean reopenIfReturned(SessionView session) {
        if (session.getTicketId() == null) {
            return false;
        }
        if (session.getState() != SessionState.TICKETED) {
            return false;
        }

        Ticket ticket = ticketMapper.selectById(session.getTicketId());
        if (ticket == null || !RETURNED.equals(ticket.getStatus())) {
            return false;
        }

        session.setState(SessionState.CHATTING);
        // 打回是人工明确要求补全，所以追问预算重新给一次：
        // 否则顾客刚要回答，AI 就直接把他推到确认卡上了。
        session.setFollowupCount(0);
        session.setDraftSummary(null);

        String ask = buildAsk(session, ticket);
        sessionStore.appendMessage(session.getId(), "assistant", ask);
        sessionStore.save(session);

        log.info("会话 {} 因工单 {} 被打回而重开（第 {} 次）",
                session.getId(), ticket.getId(), ticket.getReturnCount());
        return true;
    }

    /** 工单当前缺哪些必填项（按中文字段名） */
    public List<String> missingLabels(SessionView session) {
        TemplateSchema schema = templateService.getSchema(session.getTemplateId());
        MissingReport missing = missingAnalyzer.analyze(schema, session.getValues());
        List<String> labels = new ArrayList<>();
        for (String key : missing.required()) {
            labels.add(labelOf(schema, key));
        }
        return labels;
    }

    /**
     * 追问话术：人工留了话就用人工的，否则按缺失字段自动生成。
     * 人工的话优先——他知道顾客那边到底卡在哪，比字段名准确。
     */
    private String buildAsk(SessionView session, Ticket ticket) {
        if (ticket.getReturnNote() != null && !ticket.getReturnNote().isBlank()) {
            return ticket.getReturnNote().trim();
        }

        List<String> labels = missingLabels(session);
        if (labels.isEmpty()) {
            return "人工客服需要您再补充一些情况，请直接回复说明。";
        }
        return "人工客服看过这张工单了，还需要您补充：" + String.join("、", labels)
                + "。直接回复即可，我会重新整理后提交。";
    }

    private String labelOf(TemplateSchema schema, String key) {
        return schema.find(key)
                .map(f -> f.getLabel() == null || f.getLabel().isBlank() ? key : f.getLabel())
                .orElse(key);
    }
}
