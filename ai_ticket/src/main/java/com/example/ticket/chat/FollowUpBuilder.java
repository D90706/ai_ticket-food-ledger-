package com.example.ticket.chat;

import com.example.ticket.template.FieldDef;
import com.example.ticket.template.TemplateSchema;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 追问话术生成器。
 *
 * 刻意不交给模型：追问只需要说清「缺什么」，
 * 这是一次确定性拼装。
 * 交给模型只会引入两类无谓的风险——措辞飘忽、以及凭空承诺。
 *
 * 用字段的中文名而不是 key，因为这句话是给顾客看的。
 */
@Component
public class FollowUpBuilder {

    /**
     * @param askedBefore 这个会话已经追问过几次（0 表示这是第一次）
     *
     * 追问话术随轮次变化。原因很实际：实测端到端跑下来，
     * 同一个缺失项第二次追问如果一字不差地重复上一句，
     * 顾客会觉得系统没在听他说话。
     */
    public String build(TemplateSchema schema, List<String> missingRequiredKeys,
                        int askedBefore) {
        List<String> labels = new ArrayList<>(missingRequiredKeys.size());
        for (String key : missingRequiredKeys) {
            labels.add(schema.find(key)
                    .map(this::labelOf)
                    .orElse(key));
        }
        String joined = String.join("、", labels);
        if (askedBefore <= 0) {
            return "为了帮您处理，还需要确认：" + joined + "。";
        }
        return "还差一点信息就能处理了：请提供" + joined + "。";
    }

    /** 出单时给顾客的结束语，同样是确定性文案 */
    public String ticketNotice(String levelLabel) {
        return "已为您整理成工单并转交人工处理"
                + (levelLabel == null || levelLabel.isBlank() ? "。" : "（" + levelLabel + "）。");
    }

    private String labelOf(FieldDef field) {
        return field.getLabel() == null || field.getLabel().isBlank()
                ? field.getKey() : field.getLabel();
    }
}
