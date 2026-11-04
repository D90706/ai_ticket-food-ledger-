package com.example.ticket.chat;

import com.example.ticket.chat.dto.TicketStatusView;
import com.example.ticket.common.BizException;
import com.example.ticket.ticket.Ticket;
import com.example.ticket.ticket.TicketMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 工单状态回执。
 *
 * 这是「人工处理完之后，顾客怎么知道」的答案。
 *
 * 四个刻意的决定：
 *
 * 1 不为它调用模型。
 *    工单状态是确定性的数据库字段，让模型去「检查」只会多一次出错的机会
 *    （它可能把 ASSIGNED 说成已解决）。状态由数据库给，文案由代码拼。
 *
 * 2 回执落库且幂等。
 *    会话上记 last_notified_status，只有状态跨过节点才写一条消息。
 *    否则顾客每次轮询都会刷出一条「已受理」，那是噪音不是通知。
 *
 * 3 通知是懒的（顾客来问才写）。
 *    不做推送、不做定时任务。代价是顾客关掉页面就不会被通知——
 *    这一点写在 README 的已知缺口里。
 *
 * 4 打回的追问只有一条来源。
 *    ReturnFlow 重开会话时已经把「需要补什么」写进会话了，
 *    这里不再追加一条相似的回执，否则顾客会收到两遍几乎一样的话。
 *    做法：把那条已有的消息作为 notice 回给前端，不重复落库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatStatusService {

    private final SessionStore sessionStore;
    private final TicketMapper ticketMapper;
    private final ReturnFlow returnFlow;

    public TicketStatusView check(Long sessionId) {
        SessionView session = sessionStore.load(sessionId);
        if (session.getTicketId() == null) {
            // 还没出单，没什么可回执的
            return null;
        }

        Ticket ticket = ticketMapper.selectById(session.getTicketId());
        if (ticket == null) {
            throw BizException.notFound("工单 " + session.getTicketId() + " ");
        }

        // 工单被打回：先把会话重开（这一步会往会话里写一条追问）
        boolean reopened = returnFlow.reopenIfReturned(session);

        TicketStatusView view = new TicketStatusView();
        view.setTicketId(ticket.getId());
        view.setStatus(ticket.getStatus());
        view.setDecision(ticket.getDecision());

        String lastNotified = session.getLastNotifiedStatus();
        boolean changed = !ticket.getStatus().equals(lastNotified);

        if (changed && reopened) {
            // 追问已经在会话里了，把它作为这次回执带给前端，不重复写
            view.setNotice(lastAssistantMessage(sessionId));
            view.setChanged(true);
            markNotified(session, ticket.getStatus());
            return view;
        }

        String notice = changed ? noticeOf(ticket, lastNotified) : null;
        if (notice == null) {
            view.setChanged(false);
            return view;
        }

        sessionStore.appendMessage(sessionId, "assistant", notice);
        markNotified(session, ticket.getStatus());
        view.setNotice(notice);
        view.setChanged(true);
        log.info("会话 {} 收到工单 {} 状态回执：{}", sessionId, ticket.getId(), ticket.getStatus());
        return view;
    }

    private void markNotified(SessionView session, String status) {
        session.setLastNotifiedStatus(status);
        sessionStore.save(session);
    }

    private String lastAssistantMessage(Long sessionId) {
        List<ChatMessage> history = sessionStore.history(sessionId);
        for (int i = history.size() - 1; i >= 0; i--) {
            if ("assistant".equals(history.get(i).getRole())) {
                return history.get(i).getContent();
            }
        }
        return null;
    }

    /**
     * 只有「人工真的动过」的状态才通知。
     * PENDING / INFO_INCOMPLETE 是生成时的初始状态，
     * 顾客已经在出单那一刻被告知过了，再通知就是重复。
     * 例外：PENDING 如果是从 RETURNED 回来的，那是「补全后重新提交」，要通知。
     */
    private String noticeOf(Ticket ticket, String lastNotified) {
        String id = "#" + ticket.getId();
        return switch (ticket.getStatus()) {
            case "ASSIGNED" -> "您的工单 " + id + " 已由人工客服受理，正在处理中。";
            case "PROCESSING" -> "您的工单 " + id + " 正在处理中。";
            case "RETURNED" -> "人工客服需要您补充一些信息才能继续处理工单 " + id
                    + "，请在对话里回复。";
            case "PENDING" -> "RETURNED".equals(lastNotified)
                    ? "补充的信息已收到，工单 " + id + " 已重新提交，客服会尽快处理。"
                    : null;
            case "RESOLVED" -> ticket.getDecision() == null || ticket.getDecision().isBlank()
                    ? "您的工单 " + id + " 已处理完成。"
                    : "您的工单 " + id + " 已处理完成，处理结果：" + ticket.getDecision() + "。";
            case "CLOSED" -> "您的工单 " + id + " 已关闭。";
            default -> null;
        };
    }
}
