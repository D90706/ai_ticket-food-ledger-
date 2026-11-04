package com.example.ticket.quality;

import com.example.ticket.template.FieldDef;
import com.example.ticket.template.FieldType;
import com.example.ticket.template.TemplateSchema;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 自洽性检查：字段之间合不合得来。
 *
 * 这里刻意只做「与业务无关」的通用规则，且只产出**提示**，不参与定级。
 * 三个理由：
 *
 * 1. 业务规则（「尺码不合适应当建议换货」）需要表达式引擎，
 *    那会把项目从「对话 → 结构化」变成「对话 → 规则引擎」。
 *
 * 2. 通用规则里能确定的东西很少。比如「日期不得晚于今天」，
 *    对「问题最早出现日期」成立，对「期望送达日期」就是误报——
 *    通用引擎分不清两者，所以只能降为提示。
 *
 * 3. 自洽性是三个维度里可计算性最差的一个。
 *    让一个最不可信的维度去改变级别结论，
 *    等于用一个估计去修正两个已知（idea.txt 9.8）。
 */
@Component
public class ConsistencyChecker {

    public Result check(TemplateSchema schema, Map<String, Object> values,
                        LocalDateTime now) {
        List<Defect> violations = new ArrayList<>();
        List<Defect> advisories = new ArrayList<>();
        LocalDate today = now.toLocalDate();

        for (FieldDef field : schema.getFields()) {
            if (field.getType() != FieldType.DATE) {
                continue;
            }
            Object raw = values == null ? null : values.get(field.getKey());
            if (raw == null) {
                continue;
            }
            LocalDate date;
            try {
                date = LocalDate.parse(String.valueOf(raw));
            } catch (Exception e) {
                // 格式问题属于结构校验的职责，这里不重复报
                continue;
            }
            if (date.isAfter(today)) {
                advisories.add(Defect.of("DATE_IN_FUTURE", field.getKey(),
                        "字段 " + labelOf(field) + " 的日期在未来：" + date));
            }
        }

        return new Result(violations, advisories);
    }

    public record Result(List<Defect> violations, List<Defect> advisories) {
    }

    private String labelOf(FieldDef field) {
        return field.getLabel() == null || field.getLabel().isBlank()
                ? field.getKey() : field.getLabel();
    }
}
