package com.example.ledger.record;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 合规判定结果。
 *
 * 存的不只是结论，而是"为什么"：
 * 半年后有人问「这笔为什么不让入账」，要能立刻答出来。
 */
@Data
public class LedgerQuality {

    private QualityLevel level;

    /** 缺了哪几项 */
    private List<Missing> missing = new ArrayList<>();

    /** 完整性比率，用于列表排序与统计 */
    private Map<String, Double> metrics = new LinkedHashMap<>();

    /** 不影响合规但要看一眼的提示：临期、已过期、证照到期 */
    private List<String> warnings = new ArrayList<>();

    /** 给人看的一句话 */
    private List<String> messages = new ArrayList<>();

    @Data
    public static class Missing {
        private String key;
        private String label;
        private boolean required;

        public Missing(String key, String label, boolean required) {
            this.key = key;
            this.label = label;
            this.required = required;
        }
    }

    public boolean blocksPosting() {
        return level == QualityLevel.RED;
    }
}