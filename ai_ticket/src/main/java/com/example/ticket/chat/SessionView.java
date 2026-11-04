package com.example.ticket.chat;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 会话的领域视图（不是数据库实体）。
 *
 * 存在的理由：编排层关心的是「还缺什么、问过几次」，
 * 不关心这些值怎么序列化进 MySQL。把 JSON 转换收在 DbSessionStore 里，
 * 编排逻辑就能脱离数据库测试。
 *
 * collectedValues 必须跨轮次累积：
 * 顾客第二次补充订单号时，模型未必会把上一轮的原因一起复述出来。
 */
@Data
public class SessionView {

    private Long id;
    private Long templateId;
    private int templateVersion;
    private int followupCount;
    private SessionState state = SessionState.CHATTING;
    private Map<String, Object> values = new LinkedHashMap<>();
    private Map<String, String> basis = new LinkedHashMap<>();
    /** 待确认的一句话摘要；顾客在确认卡里可以改 */
    private String draftSummary;

    /** 已经通知过顾客的工单状态；用于「同一状态只回执一次」 */
    private String lastNotifiedStatus;

    private Long ticketId;

    /**
     * 合并本轮抽取结果。
     *
     * 合并规则：新值非空则覆盖，为空则保留旧值。
     * 反过来（清空）会让顾客补充一句话就把之前的信息丢掉。
     */
    public void merge(Map<String, Object> newValues, Map<String, String> newBasis) {
        if (newValues != null) {
            newValues.forEach((k, v) -> {
                if (v != null) {
                    values.put(k, v);
                } else {
                    values.putIfAbsent(k, null);
                }
            });
        }
        if (newBasis != null) {
            newBasis.forEach((k, v) -> {
                if (v != null) {
                    basis.put(k, v);
                } else {
                    basis.putIfAbsent(k, null);
                }
            });
        }
    }
}
