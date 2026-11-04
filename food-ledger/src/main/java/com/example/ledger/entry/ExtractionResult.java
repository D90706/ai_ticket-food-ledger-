package com.example.ledger.entry;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次抽取的结果。
 *
 * 只有三样东西，和旧系统的结论一致：
 *   values   字段值 —— 唯一的事实来源
 *   basis    原文依据 —— 每个值对应哪一句话
 *   summary  一句话概括
 *
 * 注意这里没有「建议」：台账不需要模型建议怎么处理，
 * 只需要模型说清看到了什么。
 */
@Data
public class ExtractionResult {

    private Map<String, Object> values = new LinkedHashMap<>();

    private Map<String, String> basis = new LinkedHashMap<>();

    private String summary;

    /** 调用与重试次数，用于观测 */
    private int attempts;

    /** 降级原因；为空表示正常 */
    private String degradedReason;

    /** 模型给出的依据总数（用于算编造率的分母） */
    private int basisGiven;

    /** 因「原文里找不到」而被丢弃的依据数 —— 编造率的分子 */
    private int basisDropped;

    public boolean isDegraded() {
        return degradedReason != null;
    }
}