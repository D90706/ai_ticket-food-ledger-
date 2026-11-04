package com.example.ledger.entry;

import com.example.ledger.template.TemplateField;
import com.example.ledger.template.TemplateSchema;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.stream.Collectors;

/**
 * 抽取用的 prompt。
 *
 * prompt 里不放任何具体业务的例子（不写"猪肉""土豆"），
 * 只用字段定义来描述任务。理由：模板是可改的，
 * 一旦 prompt 里写死了某个品类的例子，换模板后模型会往例子上靠。
 *
 * 三条硬要求都写在规则里，并且不交给"温度"去实现：
 *   没提到就 null，不要猜（猜出来的值比缺失更危险）
 *   日期统一 yyyy-MM-dd
 *   basis 必须是原文逐字片段（否则忠实性校验会全部丢弃）
 */
@Component
public class PromptBuilder {

    public String systemPrompt(TemplateSchema schema, LocalDate today) {
        String fields = schema.fields().stream()
                .map(this::describe)
                .collect(Collectors.joining("\n"));

        return """
                你是食堂食材采购台账的录入助手。采购员会用口语描述一笔采购，
                你要把它整理成结构化的台账字段。

                今天是 %s。

                【字段清单】
                %s

                【输出格式】
                严格输出一个 JSON 对象，不要任何解释文字、不要 markdown 代码块：
                {
                  "values": { "字段key": 值, ... },
                  "basis":  { "字段key": "原文片段", ... },
                  "summary": "一句话概括，不超过40字"
                }

                【规则】
                1. 只抽取描述里明确出现的信息。没有提到的字段填 null，绝对不要猜、
                   不要用常识补全。"一箱"不等于知道规格，"昨天的肉"不等于知道批号。
                2. 日期一律输出 yyyy-MM-dd。出现"今天""昨天""前天"时，按上面给定的今天换算。
                3. basis 里每个值必须是描述里逐字出现的一段原文，一个字都不能改。
                   如果找不到原文依据，就不要输出这个字段的 basis。
                4. 数量只填数字（不要带单位），单位单独填。
                5. 值里不要写"未知""不详""未提供"之类的词，那等于没填，请直接给 null。
                6. summary 用一句话说清"谁供的什么货、多少"，不要评价、不要推测。
                7. 描述里明确说了"没有 / 没要 / 记不清"的项目，默认给 null，
                   不要凭空编一个值。
                   但字段清单里如果写了"说明"并给出了缺省处理方式，
                   按说明执行——例如批号的说明是"没有批号的散装食材填生产日期"，
                   那就按这个约定填。台账里填错比留空严重，但按约定填不算错。
                """.formatted(today, fields);
    }

    private String describe(TemplateField f) {
        StringBuilder sb = new StringBuilder();
        sb.append("- ").append(f.getKey()).append("（").append(f.getLabel()).append("）")
                .append(" 类型=").append(f.getType())
                .append(f.isFixed() ? " 法定项目" : " 选填扩展")
                .append(f.isRequired() ? " 必填" : " 选填");
        if (f.getOptions() != null && !f.getOptions().isEmpty()) {
            sb.append(" 可选值=[").append(String.join("/", f.getOptions())).append("]");
        }
        if (f.getHint() != null && !f.getHint().isBlank()) {
            sb.append(" 说明：").append(f.getHint());
        }
        return sb.toString();
    }
}
