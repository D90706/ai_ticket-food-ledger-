package com.example.ticket.extraction;

import com.example.ticket.llm.Message;
import com.example.ticket.retrieval.Retriever;
import com.example.ticket.template.FieldDef;
import com.example.ticket.template.FieldType;
import com.example.ticket.template.TemplateSchema;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 把模板拼成 prompt。
 *
 * 这是全项目最核心的类之一：它决定「一套 prompt 能不能适配任意 schema」。
 * 所以整个拼装过程中不允许出现任何业务词——
 * 不出现「订单」「售后」「维修」这类字眼，全部由 schema 驱动。
 *
 * 拼装顺序固定为七段（idea.txt 7.5）：
 *   1 角色      2 领域知识    3 决策选项   4 字段清单
 *   5 输出契约  6 硬规则      7 few-shot
 *
 * 无状态：同一份 schema 重复调用结果一致（日期除外），便于单测。
 */
@Component
@RequiredArgsConstructor
public class PromptBuilder {

    private final Retriever retriever;
    private final FewShotGenerator fewShotGenerator;

    public List<Message> build(TemplateSchema schema, String dialogue) {
        return build(schema, dialogue, LocalDate.now());
    }

    public List<Message> build(TemplateSchema schema, String dialogue, LocalDate today) {
        return List.of(
                Message.system(systemPrompt(schema, dialogue, today)),
                Message.user("【对话内容】\n" + dialogue));
    }

    /** 包可见，方便单测逐段断言 */
    String systemPrompt(TemplateSchema schema, String dialogue, LocalDate today) {
        StringBuilder sb = new StringBuilder();

        // 角色单独成段，不套「你是……」。因为 role 由用户填写，
        // 用户可能已经写成「你是XX助手」，套一层会变成「你是「你是XX」」。
        sb.append("【角色】\n").append(nullToDash(schema.getRole())).append("\n\n");

        sb.append("【领域知识】\n");
        List<String> chunks = retriever.retrieve(dialogue, schema);
        sb.append(chunks.isEmpty() ? "（本次未提供领域知识）" : String.join("\n", chunks));
        sb.append("\n\n");

        sb.append("【可用决策选项】\n");
        sb.append("只能从下列选项中选择一个，禁止自创：\n");
        sb.append(String.join(" / ", schema.getDecisionOptions()));
        sb.append("\n\n");

        sb.append("【需要抽取的字段】\n");
        for (FieldDef field : schema.getFields()) {
            sb.append("- ").append(field.getKey())
                    .append(" | ").append(nullToDash(field.getLabel()))
                    .append(" | ").append(field.getType() == null ? "-" : field.getType().getCode())
                    .append(" | ").append(field.isRequired() ? "必填" : "选填");
            if (field.getType() == FieldType.ENUM
                    && field.getOptions() != null && !field.getOptions().isEmpty()) {
                sb.append(" | 枚举候选: ").append(String.join("、", field.getOptions()));
            }
            if (field.hasPattern()) {
                sb.append(" | 格式要求: ").append(field.getPattern());
            }
            sb.append('\n');
        }
        sb.append('\n');

        sb.append("【当前日期】\n").append(today).append("\n\n");

        sb.append("""
                【输出要求】
                只输出一个 JSON 对象，不要解释、不要 markdown 代码块、不要多余文字。结构必须是：
                {
                  "values":     { "字段key": 值 },
                  "basis":      { "字段key": "该值在对话中的原文片段" },
                  "suggestion": "决策选项之一 或 null",
                  "summary":    "一句话概述"
                }
                values 与 basis 的键必须与上面列出的字段 key 完全一致，不能增、不能减。

                【硬规则】
                1. 无法从对话中确定的值一律填 null，禁止猜测、禁止编造、禁止用常识补全。
                2. enum 字段只能取给定候选值之一，否则填 null。
                3. boolean 字段只能输出 true 或 false；对话未明确说明、或说明存在歧义时，
                   一律填 null，不要替用户下结论。
                4. date 字段输出 yyyy-MM-dd，相对时间按【当前日期】换算；
                   无法精确到日时填 null，不要凑一个日期。
                5. image 类型字段你无法处理，一律填 null。
                6. 每个非 null 的值都必须在 basis 中给出对话原文片段，
                   且必须是逐字摘录，不得改写、不得拼接、不得意译。
                7. summary 用一句话概述用户诉求与情绪，不超过 60 字，不要复述字段。
                8. suggestion 只能取【可用决策选项】之一；信息不足以判断时为 null。

                """);

        FewShot fewShot = fewShotGenerator.generate(schema);
        sb.append("【示例】\n");
        sb.append("假设对话是：「").append(fewShot.dialogue()).append("」\n");
        sb.append("则应输出：\n");
        sb.append(fewShot.exampleJson()).append('\n');

        return sb.toString();
    }

    private String nullToDash(String s) {
        return (s == null || s.isBlank()) ? "-" : s;
    }
}
