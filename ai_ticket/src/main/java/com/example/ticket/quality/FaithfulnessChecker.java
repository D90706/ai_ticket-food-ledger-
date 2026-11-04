package com.example.ticket.quality;

import com.example.ticket.template.FieldDef;
import com.example.ticket.template.TemplateSchema;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 忠实性检查：对每个有值的字段，看依据是否存在、是否真的在原文里。
 *
 * 三项校验（idea.txt 9.5）：
 *   1 依据是否存在
 *   2 依据是否为对话原文的子串
 *   3 依据覆盖率 = 有依据的非空字段 / 非空字段
 *
 * 第 2 项是核心。它是「编造引用」的唯一免费检测手段，
 * 代价只是一次 contains()。
 */
@Component
public class FaithfulnessChecker {

    public Result check(TemplateSchema schema, Map<String, Object> values,
                        Map<String, String> basis, String dialogue) {
        List<BasisCheck> items = new ArrayList<>();
        List<Defect> defects = new ArrayList<>();
        String text = dialogue == null ? "" : dialogue;

        int nonNull = 0;
        int withBasis = 0;

        for (FieldDef field : schema.getFields()) {
            Object value = values == null ? null : values.get(field.getKey());
            if (value == null) {
                continue;
            }
            nonNull++;

            String b = basis == null ? null : basis.get(field.getKey());
            boolean has = b != null && !b.isBlank();
            boolean inDialogue = has && text.contains(b);
            if (has) {
                withBasis++;
            }
            items.add(new BasisCheck(field.getKey(), has, inDialogue));

            String label = labelOf(field);
            if (!has) {
                defects.add(Defect.of("BASIS_MISSING", field.getKey(),
                        "字段 " + label + " 有值，但没有给出原文依据"));
            } else if (!inDialogue) {
                defects.add(Defect.of("BASIS_NOT_IN_DIALOGUE", field.getKey(),
                        "字段 " + label + " 的依据不是对话原文：\""
                                + abbreviate(b) + "\""));
            }
        }

        double coverage = nonNull == 0 ? 0.0 : round3((double) withBasis / nonNull);
        return new Result(items, defects, coverage);
    }

    /**
     * 注意 coverage 的分母是「非空字段数」。
     * 一个字段全为 null 的工单，严格说覆盖率无定义；
     * 这里统一取 0.0 而不是 null，避免前端到处判空。
     */
    public record Result(List<BasisCheck> items, List<Defect> defects, double coverage) {
    }

    private String labelOf(FieldDef field) {
        return field.getLabel() == null || field.getLabel().isBlank()
                ? field.getKey() : field.getLabel();
    }

    private String abbreviate(String s) {
        return s.length() <= 30 ? s : s.substring(0, 30) + "...";
    }

    static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
