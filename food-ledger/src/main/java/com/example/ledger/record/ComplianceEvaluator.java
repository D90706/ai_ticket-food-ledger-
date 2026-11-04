package com.example.ledger.record;

import com.example.ledger.template.TemplateField;
import com.example.ledger.template.TemplateSchema;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 合规判定：这笔采购的记录够不够得上"台账"。
 *
 * 判定规则来自模板，不写死在代码里：
 *   required 字段（法定 12 项 + 单位自己标必填的）缺 → RED，不许入账
 *   required 齐、但还有选填缺或凭证不全 → YELLOW，可入账但要补
 *   全齐 → GREEN
 *
 * 为什么不用模型判断：
 *   这是确定性规则，用模型只会引入波动，而且同一笔采购
 *   两次判定给出不同结论，台账就没法解释了。
 *
 * 临期/过期不算级别，只算提示：
 *   过期是业务事实（该退就退），不是"记录不全"。
 *   把两件事混成一个分数，人就分不清该补资料还是该退货。
 */
public final class ComplianceEvaluator {

    /** 索证索票凭证的字段 key：这一项允许用「照片」替代「编号」 */
    private static final String CERTIFICATE_KEY = "certificate";

    private final TemplateSchema schema;

    public ComplianceEvaluator(TemplateSchema schema) {
        this.schema = schema;
    }

    /**
     * @param values 字段值（法定项目来自固定列，自定义字段来自 extra_json，合并成一张表）
     * @param today  今天。传进来而不是内部取，测试才能稳定
     */
    public LedgerQuality evaluate(Map<String, Object> values, LocalDate today) {
        LedgerQuality quality = new LedgerQuality();

        List<LedgerQuality.Missing> missingRequired = new ArrayList<>();
        List<LedgerQuality.Missing> missingOptional = new ArrayList<>();

        for (TemplateField field : schema.fields()) {
            if (!isBlank(values.get(field.getKey()))) {
                continue;
            }
            // 凭证这一项，「照片」和「编号」等效。
            // 法律要求的是「保存相关凭证」，一串编号本身不是凭证：
            // 只有编号没有照片，等于把"证明"换成了"证明的编号"。
            // 所以只要挂上了附件，凭证就算有。
            if (CERTIFICATE_KEY.equals(field.getKey()) && hasAttachment(values)) {
                continue;
            }
            (field.isRequired() ? missingRequired : missingOptional)
                    .add(new LedgerQuality.Missing(field.getKey(), field.getLabel(), field.isRequired()));
        }

        quality.getMissing().addAll(missingRequired);
        quality.getMissing().addAll(missingOptional);

        long requiredTotal = schema.requiredFields().size();
        long requiredFilled = requiredTotal - missingRequired.size();
        long allTotal = schema.fields().size();
        long allFilled = allTotal - missingRequired.size() - missingOptional.size();

        quality.getMetrics().put("requiredRate", ratio(requiredFilled, requiredTotal));
        quality.getMetrics().put("fillRate", ratio(allFilled, allTotal));

        // ---- 级别 ----
        if (!missingRequired.isEmpty()) {
            quality.setLevel(QualityLevel.RED);
            quality.getMessages().add("缺 " + missingRequired.size() + " 项法定项目："
                    + labels(missingRequired) + "——补齐前不能入账");
        } else if (!missingOptional.isEmpty()) {
            quality.setLevel(QualityLevel.YELLOW);
            /*
             * 这句必须写清「不影响入账」。
             * 原来的文案是「缺 3 项选填：产地、冷链到货温度、农残检测报告号」——
             * 读起来像是必须补全才能往下一步，于是采购员对着这三项反复保存，
             * 状态一直是「待入账」，以为系统卡住了。
             * 实际上待补证是可以入账的，缺的只是选填信息。
             */
            quality.getMessages().add("缺 " + missingOptional.size() + " 项选填："
                    + labels(missingOptional) + "（不影响入账，可后续补）");
        } else {
            quality.setLevel(QualityLevel.GREEN);
        }

        // ---- 提示（不影响级别）----
        checkExpiry(values, today, quality);

        return quality;
    }

    /** 到期日提示：已过期是"该退货"，临期是"先用"，两者都不改级别 */
    private void checkExpiry(Map<String, Object> values, LocalDate today, LedgerQuality quality) {
        Object raw = values.get("expiry_date");
        if (raw == null) {
            // 没有到期日就无法预警，但对保质期长的食材（米面油）是正常的
            Object shelf = values.get("shelf_life");
            if (!isBlank(shelf)) {
                quality.getWarnings().add("未填写到期日，无法做临期预警");
            }
            return;
        }
        LocalDate expiry = raw instanceof LocalDate d ? d : LocalDate.parse(String.valueOf(raw));
        long days = ChronoUnit.DAYS.between(today, expiry);
        if (days < 0) {
            quality.getWarnings().add("已过保质期 " + (-days) + " 天，不得入库使用");
        } else if (days <= schema.shelfLifeWarnDays()) {
            quality.getWarnings().add("距到期仅剩 " + days + " 天，建议优先使用");
        }
    }

    private String labels(List<LedgerQuality.Missing> missing) {
        return missing.stream().map(LedgerQuality.Missing::getLabel).collect(Collectors.joining("、"));
    }

    private double ratio(long filled, long total) {
        if (total <= 0) {
            return 1.0;
        }
        return BigDecimal.valueOf(filled)
                .divide(BigDecimal.valueOf(total), 3, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private boolean isBlank(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String s) {
            return s.isBlank();
        }
        return false;
    }

    /** 值表里挂了附件没有（附件是从 extra_json 之外的 attachments 字段带进来的） */
    private boolean hasAttachment(Map<String, Object> values) {
        Object attachments = values.get("attachments");
        if (attachments instanceof java.util.Collection<?> c) {
            return !c.isEmpty();
        }
        if (attachments instanceof String s) {
            return !s.isBlank() && !"[]".equals(s.trim());
        }
        return false;
    }
}
