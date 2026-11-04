package com.example.ticket.extraction;

import java.util.List;

/**
 * 校验结果。
 *
 * 注意这里区分两种「不合法」：
 *   结构违规（本类）—— 值不符合字段定义，重试可能修好，属于抽取层的事
 *   信息缺失（核验层）—— 值本来就是 null，合法，只是不够用，属于质量层的事
 * 把两者混在一起，就会出现「因为没抽到订单号而反复重试」这种浪费。
 */
public record ValidationResult(List<Violation> violations) {

    public static ValidationResult ok() {
        return new ValidationResult(List.of());
    }

    public boolean isValid() {
        return violations.isEmpty();
    }

    public boolean hasViolations() {
        return !violations.isEmpty();
    }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        for (Violation v : violations) {
            sb.append("- ").append(v.message()).append('\n');
        }
        return sb.toString();
    }
}
