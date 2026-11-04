package com.example.ticket.quality;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 核验层的输出，结构与 mock/ticket.json 的 qualityDetail 完全一致。
 *
 * 它同时装四个东西：
 *   结论（level）
 *   理由（blocking / degrading / advisories 与 messages）
 *   证据（faithfulness.checkItems）
 *   度量（metrics 的四个比率）
 *
 * 为什么结论与理由都要存：
 *   工单上要显示的是「缺哪两项」，不是「68 分」。
 *   伪精确的数字会让人反复琢磨分数怎么来的，
 *   而「缺哪两项」是能立刻行动的信息。
 */
@Data
public class QualityReport {

    private QualityLevel level;

    private Decision decision = new Decision();

    private Faithfulness faithfulness = new Faithfulness();

    private Consistency consistency = new Consistency();

    private Metrics metrics = new Metrics();

    /** 给人看的原因句子，按 必填 → 选填 → 依据 → 摘要 → 提示 排列 */
    private List<String> messages = new ArrayList<>();

    /** 可决策性：门槛，决定红 */
    @Data
    public static class Decision {
        private List<Defect> blocking = new ArrayList<>();
        private List<Defect> degrading = new ArrayList<>();
        private List<String> missingRequired = new ArrayList<>();
        private List<String> missingOptional = new ArrayList<>();
    }

    /** 忠实性：依据校验，决定黄绿 */
    @Data
    public static class Faithfulness {
        private List<BasisCheck> checkItems = new ArrayList<>();
        private double coverage;
    }

    /** 自洽性：只产出提示，不参与定级 */
    @Data
    public static class Consistency {
        private List<Defect> violations = new ArrayList<>();
        private List<Defect> advisories = new ArrayList<>();
    }

    /** 四个连续量，用于趋势与模板体检，不用于定级 */
    @Data
    public static class Metrics {
        private double fillRate;
        private double requiredRate;
        private double optionalCoverage;
        private double basisCoverage;
    }
}
