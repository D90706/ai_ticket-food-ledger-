package com.example.ticket.chat;

import java.util.List;

/**
 * 一轮对话的结果。三种可能：
 *
 *   ASK      继续追问（缺必填，还没到上限）
 *   CONFIRM  信息不全但已问满上限，把整理好的一句话交给顾客确认后才入队
 *   TICKET   直接出单
 *
 * 这个类型存在的意义：让「分支只有一处」在类型上就成立。
 * 调用方不需要自己判断，只需要 switch。
 */
public record OrchestratorResult(
        ReplyType replyType,
        String question,
        int followupCount,
        TicketDraft draft,
        Long ticketId,
        String level,
        List<String> messages,
        String confirmSummary,
        List<String> missingLabels,
        /** TICKET 时：这张单是「打回后补全重新提交」，而不是新生成 */
        boolean resubmitted) {

    public enum ReplyType {
        ASK, CONFIRM, TICKET
    }

    public static OrchestratorResult ask(String question, int followupCount) {
        return new OrchestratorResult(ReplyType.ASK, question, followupCount,
                null, null, null, List.of(), null, List.of(), false);
    }

    public static OrchestratorResult confirm(String summary, List<String> missingLabels,
                                             String level) {
        return new OrchestratorResult(ReplyType.CONFIRM, null, 0,
                null, null, level, List.of(), summary,
                missingLabels == null ? List.of() : List.copyOf(missingLabels), false);
    }

    public static OrchestratorResult ticket(TicketDraft draft, Long ticketId,
                                            String level, List<String> messages,
                                            boolean resubmitted) {
        return new OrchestratorResult(ReplyType.TICKET, null, 0,
                draft, ticketId, level, List.copyOf(messages), null, List.of(), resubmitted);
    }
}
