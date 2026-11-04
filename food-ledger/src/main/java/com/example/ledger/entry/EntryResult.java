package com.example.ledger.entry;

import java.util.List;

/**
 * 一轮录入的结果。
 *
 *   ASK    继续追问
 *   CONFIRM 信息不全但已问满上限，给采购员看一眼再提交
 *   RECORD 已生成台账记录（状态是「待入账」——AI 不直接入账）
 */
public record EntryResult(
        ReplyType replyType,
        String question,
        int followupCount,
        Long recordId,
        String recordStatus,
        String summary,
        List<String> missingLabels,
        String level) {

    public enum ReplyType {
        ASK, CONFIRM, RECORD
    }

    public static EntryResult ask(String question, int followupCount) {
        return new EntryResult(ReplyType.ASK, question, followupCount,
                null, null, null, List.of(), null);
    }

    public static EntryResult confirm(String summary, List<String> missingLabels) {
        return new EntryResult(ReplyType.CONFIRM, null, 0, null, null, summary,
                missingLabels, null);
    }

    public static EntryResult record(Long recordId, String status, String level) {
        return new EntryResult(ReplyType.RECORD, null, 0, recordId, status, null,
                List.of(), level);
    }
}